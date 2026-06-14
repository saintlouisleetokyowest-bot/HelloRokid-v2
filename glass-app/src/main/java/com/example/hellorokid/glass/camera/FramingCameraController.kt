package com.example.hellorokid.glass.camera

import android.Manifest
import android.content.Context
import android.graphics.ImageFormat
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraAccessException
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.TotalCaptureResult
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.util.Log
import android.util.Size
import android.view.Surface
import android.view.TextureView
import androidx.annotation.RequiresPermission
import com.example.hellorokid.shared.camera.CameraCropMapper
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

/**
 * 取景模式相机：TextureView 低分辨率 preview + 高分辨率 still 拍照（session 预热后快门更快）。
 */
class FramingCameraController(private val context: Context) {

    companion object {
        private const val TAG = "FramingCamera"
        private const val CAPTURE_TIMEOUT_MS = 12_000L
        /** 定焦镜头：停 preview 后等待 AE 稳定的短暂间隔 */
        private const val AE_SETTLE_MS = 350L
        private const val AF_CALLBACK_MIN_MS = 125L
        private const val PREVIEW_READY_MIN_FRAMES = 5
        private const val STILL_JPEG_QUALITY = 95
    }

    data class CaptureInfo(
        val jpegBytes: ByteArray,
        val previewSize: Size,
        val captureSize: Size,
        val sensorOrientation: Int,
        val previewTransform: CameraCropMapper.PreviewTransform
    )

    interface PreviewReadyListener {
        /** 曝光稳定、可拍摄（定焦镜头无 AF，仅看 AE） */
        fun onPreviewReady(ready: Boolean)
    }

    private var cameraDevice: CameraDevice? = null
    private var captureSession: CameraCaptureSession? = null
    private var backgroundThread: HandlerThread? = null
    private var backgroundHandler: Handler? = null
    private var previewSurface: Surface? = null
    private var imageReader: ImageReader? = null
    private var previewSize: Size = Size(640, 480)
    private var captureSize: Size = Size(1920, 1080)
    private var sensorOrientation: Int = 0
    private var characteristics: CameraCharacteristics? = null
    private var afListener: PreviewReadyListener? = null
    private var isFixedFocusLens = false
    private var textureView: TextureView? = null
    private var lastPreviewTransform: CameraCropMapper.PreviewTransform? = null
    @Volatile
    private var awaitingStillJpeg = false
    private var lastAfCallbackMs = 0L
    private var lastLoggedAfState: Int? = null
    private var previewFrameCount = 0

    @RequiresPermission(Manifest.permission.CAMERA)
    suspend fun startFraming(textureView: TextureView, listener: PreviewReadyListener): Result<Unit> =
        withContext(Dispatchers.IO) {
            stopInternal()
            afListener = listener
            previewFrameCount = 0
            lastLoggedAfState = null
            this@FramingCameraController.textureView = textureView

            try {
                val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
                val cameraId = selectCameraId(cameraManager)
                    ?: return@withContext Result.failure(Exception("未找到相机"))

                characteristics = cameraManager.getCameraCharacteristics(cameraId)
                val chars = characteristics!!
                sensorOrientation = chars.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 0
                isFixedFocusLens = CameraAfHelper.isFixedFocusLens(chars)
                if (isFixedFocusLens) {
                    Log.i(TAG, "Fixed-focus lens (no AF). Rokid DOF ~34cm+, hold card ~40cm from left lens.")
                }

                val streamMap = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
                    ?: return@withContext Result.failure(Exception("相机不支持成像"))

                previewSize = choosePreviewSize(
                    streamMap.getOutputSizes(SurfaceTexture::class.java) ?: emptyArray()
                )
                captureSize = chooseJpegSize(
                    streamMap.getOutputSizes(ImageFormat.JPEG) ?: emptyArray()
                )
                Log.i(
                    TAG,
                    "Preview=${previewSize.width}x${previewSize.height}, still=${captureSize.width}x${captureSize.height}, sensorOrientation=$sensorOrientation"
                )

                val ready = CompletableDeferred<Unit>()
                val openError = CompletableDeferred<Exception?>()

                startBackgroundThread()

                val surfaceTexture = textureView.surfaceTexture
                    ?: return@withContext Result.failure(Exception("预览 Surface 未就绪"))
                surfaceTexture.setDefaultBufferSize(previewSize.width, previewSize.height)
                previewSurface = Surface(surfaceTexture)

                imageReader = ImageReader.newInstance(
                    captureSize.width,
                    captureSize.height,
                    ImageFormat.JPEG,
                    2
                ).apply {
                    setOnImageAvailableListener({ reader ->
                        val image = reader.acquireNextImage() ?: return@setOnImageAvailableListener
                        try {
                            if (!awaitingStillJpeg) return@setOnImageAvailableListener
                            val buffer = image.planes[0].buffer
                            val bytes = ByteArray(buffer.remaining())
                            buffer.get(bytes)
                            if (isValidJpeg(bytes) && !stillResult.isCompleted) {
                                val transform = lastPreviewTransform
                                    ?: CameraCropMapper.buildPreviewTransform(
                                        textureView?.width ?: 0,
                                        textureView?.height ?: 0,
                                        previewSize,
                                        displayRotation(),
                                        sensorOrientation
                                    )
                                stillResult.complete(
                                    CaptureInfo(
                                        bytes,
                                        previewSize,
                                        captureSize,
                                        sensorOrientation,
                                        transform
                                    )
                                )
                            }
                        } catch (e: Exception) {
                            if (!stillResult.isCompleted) {
                                stillResult.completeExceptionally(e)
                            }
                        } finally {
                            image.close()
                        }
                    }, backgroundHandler)
                }

                cameraManager.openCamera(
                    cameraId,
                    object : CameraDevice.StateCallback() {
                        override fun onOpened(camera: CameraDevice) {
                            cameraDevice = camera
                            createPreviewSession(camera, ready, openError)
                        }

                        override fun onDisconnected(camera: CameraDevice) {
                            camera.close()
                            openError.complete(Exception("相机已断开"))
                        }

                        override fun onError(camera: CameraDevice, error: Int) {
                            camera.close()
                            openError.complete(Exception("相机错误: $error"))
                        }
                    },
                    backgroundHandler
                )

                withTimeout(8_000L) { ready.await() }
                openError.await()?.let { return@withContext Result.failure(it) }
                withContext(Dispatchers.Main) {
                    applyPreviewTransform(textureView)
                }
                Result.success(Unit)
            } catch (e: Exception) {
                Log.e(TAG, "startFraming failed", e)
                stopInternal()
                Result.failure(e)
            }
        }

    @RequiresPermission(Manifest.permission.CAMERA)
    suspend fun captureStill(): Result<CaptureInfo> = withContext(Dispatchers.IO) {
        val session = captureSession
            ?: return@withContext Result.failure(Exception("相机未在取景模式"))
        val chars = characteristics
            ?: return@withContext Result.failure(Exception("相机特性未就绪"))

        stillResult = CompletableDeferred()
        awaitingStillJpeg = false

        try {
            if (isFixedFocusLens) {
                triggerAeSettleThenStill(session, chars)
            } else {
                triggerAdjustableAfThenStill(session, chars)
            }
            val info = withTimeout(CAPTURE_TIMEOUT_MS) { stillResult.await() }
            Log.i(TAG, "Still captured: ${info.jpegBytes.size} bytes")
            Result.success(info)
        } catch (e: Exception) {
            Log.e(TAG, "captureStill failed", e)
            Result.failure(e)
        } finally {
            awaitingStillJpeg = false
        }
    }

    suspend fun stop() = withContext(Dispatchers.IO) {
        stopInternal()
    }

    private var stillResult = CompletableDeferred<CaptureInfo>()

    private fun createPreviewSession(
        camera: CameraDevice,
        ready: CompletableDeferred<Unit>,
        openError: CompletableDeferred<Exception?>
    ) {
        val preview = previewSurface ?: run {
            openError.complete(Exception("预览 Surface 无效"))
            return
        }
        val reader = imageReader ?: run {
            openError.complete(Exception("ImageReader 无效"))
            return
        }

        try {
            camera.createCaptureSession(
                listOf(preview, reader.surface),
                object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(session: CameraCaptureSession) {
                        captureSession = session
                        startPreviewRepeating(session)
                        ready.complete(Unit)
                    }

                    override fun onConfigureFailed(session: CameraCaptureSession) {
                        openError.complete(Exception("相机会话配置失败"))
                    }
                },
                backgroundHandler
            )
        } catch (e: Exception) {
            openError.complete(e)
        }
    }

    private fun startPreviewRepeating(session: CameraCaptureSession) {
        val preview = previewSurface ?: return
        val chars = characteristics ?: return
        val afModes = chars.get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES) ?: intArrayOf()
        Log.i(TAG, "Preview AF modes: ${afModes.joinToString()} fixedFocus=$isFixedFocusLens")

        val builder = session.device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
            addTarget(preview)
            set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
            set(CaptureRequest.CONTROL_AWB_MODE, CaptureRequest.CONTROL_AWB_MODE_AUTO)
            CameraAfHelper.applyPreviewAf(this, afModes)
        }

        try {
            session.setRepeatingRequest(
                builder.build(),
                object : CameraCaptureSession.CaptureCallback() {
                    override fun onCaptureCompleted(
                        session: CameraCaptureSession,
                        request: CaptureRequest,
                        result: TotalCaptureResult
                    ) {
                        val now = SystemClock.elapsedRealtime()
                        if (now - lastAfCallbackMs < AF_CALLBACK_MIN_MS) return
                        lastAfCallbackMs = now

                        previewFrameCount++
                        val aeState = result.get(CaptureResult.CONTROL_AE_STATE)
                        val ready = if (isFixedFocusLens) {
                            previewFrameCount >= PREVIEW_READY_MIN_FRAMES &&
                                CameraAfHelper.isAeReady(aeState)
                        } else {
                            isAdjustableAfReady(
                                result.get(CaptureResult.CONTROL_AF_STATE),
                                afModes
                            )
                        }
                        if (previewFrameCount % 20 == 0) {
                            Log.d(
                                TAG,
                                "Preview: ae=${aeStateName(aeState)} ready=$ready frame=$previewFrameCount fixed=$isFixedFocusLens"
                            )
                        }
                        afListener?.onPreviewReady(ready)
                    }
                },
                backgroundHandler
            )
        } catch (e: Exception) {
            Log.e(TAG, "Preview repeating failed", e)
        }
    }

    /** 定焦镜头：仅等待 AE 稳定后直接快门，跳过无效 AF 脉冲 */
    private fun triggerAeSettleThenStill(
        session: CameraCaptureSession,
        characteristics: CameraCharacteristics
    ) {
        val handler = backgroundHandler ?: return
        try {
            session.stopRepeating()
            session.abortCaptures()
        } catch (e: Exception) {
            Log.w(TAG, "Stop preview before still", e)
        }
        handler.postDelayed({
            Log.i(TAG, "Firing still: AE settle ${AE_SETTLE_MS}ms (fixed-focus, no AF)")
            triggerStillCapture(session, characteristics)
        }, AE_SETTLE_MS)
    }

    /** 支持 AF 的设备：保留近距对焦脉冲 */
    private fun triggerAdjustableAfThenStill(
        session: CameraCaptureSession,
        characteristics: CameraCharacteristics
    ) {
        val handler = backgroundHandler ?: return
        try {
            session.stopRepeating()
            session.abortCaptures()
        } catch (e: Exception) {
            Log.w(TAG, "Stop preview before still", e)
        }

        val afModes = characteristics.get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES) ?: intArrayOf()
        val preview = previewSurface ?: return

        var stillTriggered = false
        var afPulseCount = 0
        val NEAR_FOCUS_MAX_MS = 2_200L
        val NEAR_FOCUS_PULSE_GAP_MS = 280L
        val focusStartMs = SystemClock.elapsedRealtime()

        fun fireStill(reason: String) {
            if (stillTriggered || stillResult.isCompleted) return
            stillTriggered = true
            handler.removeCallbacksAndMessages(null)
            Log.i(TAG, "Firing still: $reason (after ${afPulseCount} AF pulses)")
            triggerStillCapture(session, characteristics)
        }

        fun sendAfPulse() {
            if (stillTriggered || stillResult.isCompleted) return
            afPulseCount++
            val afBuilder = session.device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                addTarget(preview)
                set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
                set(CaptureRequest.CONTROL_AWB_MODE, CaptureRequest.CONTROL_AWB_MODE_AUTO)
                CameraAfHelper.applyStillAf(this, afModes)
                if (CameraAfHelper.shouldTriggerAf(afModes)) {
                    set(CaptureRequest.CONTROL_AF_TRIGGER, CaptureRequest.CONTROL_AF_TRIGGER_START)
                }
            }
            try {
                session.capture(
                    afBuilder.build(),
                    object : CameraCaptureSession.CaptureCallback() {
                        override fun onCaptureCompleted(
                            session: CameraCaptureSession,
                            request: CaptureRequest,
                            result: TotalCaptureResult
                        ) {
                            val afState = result.get(CaptureResult.CONTROL_AF_STATE)
                            val focused = afState == CaptureResult.CONTROL_AF_STATE_FOCUSED_LOCKED
                                || afState == CaptureResult.CONTROL_AF_STATE_PASSIVE_FOCUSED
                            Log.d(TAG, "AF pulse #$afPulseCount: state=${afStateName(afState)} focused=$focused")
                            if (focused) {
                                handler.postDelayed({ fireStill("AF focused on pulse $afPulseCount") }, 150)
                            } else if (afPulseCount < 4 &&
                                SystemClock.elapsedRealtime() - focusStartMs < NEAR_FOCUS_MAX_MS
                            ) {
                                handler.postDelayed({ sendAfPulse() }, NEAR_FOCUS_PULSE_GAP_MS)
                            }
                        }

                        override fun onCaptureFailed(
                            session: CameraCaptureSession,
                            request: CaptureRequest,
                            failure: android.hardware.camera2.CaptureFailure
                        ) {
                            Log.w(TAG, "AF pulse failed: ${failure.reason}")
                            if (!stillTriggered && afPulseCount < 4) {
                                handler.postDelayed({ sendAfPulse() }, NEAR_FOCUS_PULSE_GAP_MS)
                            }
                        }
                    },
                    handler
                )
            } catch (e: Exception) {
                Log.e(TAG, "AF pulse error", e)
            }
        }

        handler.postDelayed({ fireStill("near focus max wait ${NEAR_FOCUS_MAX_MS}ms") }, NEAR_FOCUS_MAX_MS)
        sendAfPulse()
    }

    private fun isAdjustableAfReady(afState: Int?, afModes: IntArray): Boolean {
        if (afState == null) return true
        return when (afState) {
            CaptureResult.CONTROL_AF_STATE_FOCUSED_LOCKED,
            CaptureResult.CONTROL_AF_STATE_PASSIVE_FOCUSED -> true
            CaptureResult.CONTROL_AF_STATE_INACTIVE -> !CameraAfHelper.hasAdjustableAf(afModes)
            else -> false
        }
    }

    private fun aeStateName(state: Int?): String = when (state) {
        null -> "null"
        CaptureResult.CONTROL_AE_STATE_INACTIVE -> "INACTIVE"
        CaptureResult.CONTROL_AE_STATE_SEARCHING -> "SEARCHING"
        CaptureResult.CONTROL_AE_STATE_CONVERGED -> "CONVERGED"
        CaptureResult.CONTROL_AE_STATE_LOCKED -> "LOCKED"
        CaptureResult.CONTROL_AE_STATE_FLASH_REQUIRED -> "FLASH_REQUIRED"
        CaptureResult.CONTROL_AE_STATE_PRECAPTURE -> "PRECAPTURE"
        else -> "UNKNOWN($state)"
    }

    private fun afStateName(state: Int?): String = when (state) {
        null -> "null"
        CaptureResult.CONTROL_AF_STATE_INACTIVE -> "INACTIVE"
        CaptureResult.CONTROL_AF_STATE_PASSIVE_FOCUSED -> "PASSIVE_FOCUSED"
        CaptureResult.CONTROL_AF_STATE_FOCUSED_LOCKED -> "FOCUSED_LOCKED"
        else -> "AF($state)"
    }

    private fun triggerStillCapture(
        session: CameraCaptureSession,
        characteristics: CameraCharacteristics
    ) {
        val reader = imageReader ?: return
        awaitingStillJpeg = true

        val afModes = characteristics.get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES) ?: intArrayOf()

        val builder = session.device.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE).apply {
            addTarget(reader.surface)
            set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
            set(CaptureRequest.CONTROL_AWB_MODE, CaptureRequest.CONTROL_AWB_MODE_AUTO)
            CameraAfHelper.applyStillAf(this, afModes)
            if (CameraAfHelper.shouldTriggerAf(afModes)) {
                set(CaptureRequest.CONTROL_AF_TRIGGER, CaptureRequest.CONTROL_AF_TRIGGER_START)
            }
            set(CaptureRequest.JPEG_QUALITY, STILL_JPEG_QUALITY.toByte())
        }

        try {
            session.capture(
                builder.build(),
                object : CameraCaptureSession.CaptureCallback() {
                    override fun onCaptureFailed(
                        session: CameraCaptureSession,
                        request: CaptureRequest,
                        failure: android.hardware.camera2.CaptureFailure
                    ) {
                        if (!stillResult.isCompleted) {
                            stillResult.completeExceptionally(Exception("静态拍照失败"))
                        }
                    }
                },
                backgroundHandler
            )
        } catch (e: Exception) {
            if (!stillResult.isCompleted) {
                stillResult.completeExceptionally(e)
            }
        }
    }

    suspend fun applyPreviewTransform(textureView: TextureView) = withContext(Dispatchers.Main) {
        if (textureView.width == 0 || textureView.height == 0) {
            suspendCoroutine { cont ->
                textureView.post { cont.resume(Unit) }
            }
        }
        if (textureView.width == 0 || textureView.height == 0) return@withContext

        val rotation = displayRotation()
        lastPreviewTransform = CameraCropMapper.configureTransform(
            textureView,
            previewSize,
            rotation,
            sensorOrientation
        )
        Log.i(
            TAG,
            "Preview transform applied: view=${textureView.width}x${textureView.height}, " +
                "buffer=${previewSize.width}x${previewSize.height}, " +
                "sensor=$sensorOrientation°, display=$rotation, previewRotation=${lastPreviewTransform?.rotationDegrees}°"
        )
    }

    fun getPreviewTransform(): CameraCropMapper.PreviewTransform? = lastPreviewTransform

    private fun displayRotation(): Int {
        return (context as? android.app.Activity)?.display?.rotation
            ?: android.view.Surface.ROTATION_0
    }

    private suspend fun stopInternal() {
        awaitingStillJpeg = false
        afListener = null
        textureView = null
        lastPreviewTransform = null
        try {
            captureSession?.stopRepeating()
            captureSession?.abortCaptures()
            captureSession?.close()
        } catch (e: Exception) {
            Log.w(TAG, "Error closing session", e)
        }
        captureSession = null

        previewSurface?.release()
        previewSurface = null
        imageReader?.close()
        imageReader = null

        try {
            cameraDevice?.close()
        } catch (e: Exception) {
            Log.w(TAG, "Error closing camera", e)
        }
        cameraDevice = null

        delay(200)
        stopBackgroundThread()
    }

    private fun selectCameraId(cameraManager: CameraManager): String? {
        val ids = try {
            cameraManager.cameraIdList
        } catch (e: CameraAccessException) {
            emptyArray()
        }
        return ids.firstOrNull { id ->
            try {
                cameraManager.getCameraCharacteristics(id)
                    .get(CameraCharacteristics.LENS_FACING) ==
                    CameraCharacteristics.LENS_FACING_BACK
            } catch (_: Exception) {
                false
            }
        } ?: ids.firstOrNull()
    }

    private fun choosePreviewSize(sizes: Array<Size>): Size {
        if (sizes.isEmpty()) return Size(480, 640)
        // 优先 3:4 / 9:16 竖屏比例，与 HUD 竖握一致
        val portraitFriendly = sizes.filter {
            (it.height >= it.width && it.height <= 960) ||
                (it.width <= 960 && it.height <= 1280)
        }
        val pool = portraitFriendly.ifEmpty { sizes.toList() }
        val targetArea = 480 * 640
        return pool.minByOrNull { kotlin.math.abs(it.width * it.height - targetArea) }
            ?: Size(480, 640)
    }

    private fun chooseJpegSize(sizes: Array<Size>): Size {
        if (sizes.isEmpty()) return Size(1920, 1080)
        val sorted = sizes.sortedByDescending { it.width * it.height }
        return sorted.firstOrNull { it.width <= 1920 && it.height <= 1080 }
            ?: sorted.firstOrNull { it.width <= 2560 && it.height <= 1440 }
            ?: sorted.first()
    }

    private fun isValidJpeg(bytes: ByteArray): Boolean {
        return bytes.size >= 100 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte()
    }

    private fun startBackgroundThread() {
        backgroundThread = HandlerThread("FramingCamera").apply {
            start()
            backgroundHandler = Handler(looper)
        }
    }

    private fun stopBackgroundThread() {
        backgroundThread?.quitSafely()
        try {
            backgroundThread?.join()
        } catch (_: InterruptedException) {
        }
        backgroundThread = null
        backgroundHandler = null
    }
}
