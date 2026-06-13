package com.example.hellorokid.glass.camera

import android.Manifest
import android.content.Context
import android.graphics.ImageFormat
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
import androidx.annotation.RequiresPermission
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

/**
 * Rokid 眼镜相机：Camera2 完整采集管线（preview 预热 → AE/AF 收敛 → 静态拍照）。
 * 后处理（旋转、灰度、压缩）在 [com.example.hellorokid.shared.image.ImageBleProcessor]。
 */
class RokidCameraManager(private val context: Context) {

    companion object {
        private const val TAG = "RokidCameraManager"
        private const val CAPTURE_TIMEOUT_MS = 12_000L
        private const val MAX_ATTEMPTS = 2
        private const val MIN_PREVIEW_FRAMES = 6
        private const val MAX_WARMUP_FRAMES = 14
        private const val WARMUP_TIMEOUT_MS = 2_500L
    }

    private var cameraDevice: CameraDevice? = null
    private var captureSession: CameraCaptureSession? = null
    private var backgroundThread: HandlerThread? = null
    private var backgroundHandler: Handler? = null
    private var imageReader: ImageReader? = null
    private var warmupTimeoutRunnable: Runnable? = null
    @Volatile
    private var awaitingStillJpeg = false

    @RequiresPermission(Manifest.permission.CAMERA)
    suspend fun capturePhotoJpeg(): Result<ByteArray> = withContext(Dispatchers.IO) {
        repeat(MAX_ATTEMPTS) { attempt ->
            val attemptNo = attempt + 1
            Log.d(TAG, "=== Capture attempt $attemptNo/$MAX_ATTEMPTS ===")
            val result = captureOnce()
            if (result.isSuccess) {
                return@withContext result
            }
            val error = result.exceptionOrNull()
            val timedOut = error?.message?.contains("Timed out", ignoreCase = true) == true
                || error?.message?.contains("超时", ignoreCase = true) == true
            if (!timedOut || attemptNo >= MAX_ATTEMPTS) {
                return@withContext Result.failure(
                    if (timedOut) Exception("拍照超时，请重试")
                    else error ?: Exception("拍照失败")
                )
            }
            Log.w(TAG, "Attempt $attemptNo timed out, retrying...")
            delay(300)
        }
        Result.failure(Exception("拍照超时，请重试"))
    }

    @RequiresPermission(Manifest.permission.CAMERA)
    private suspend fun captureOnce(): Result<ByteArray> {
        try {
            val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager

            val cameraIdList = try {
                cameraManager.cameraIdList
            } catch (e: CameraAccessException) {
                Log.e(TAG, "Cannot get camera list", e)
                emptyArray()
            }

            if (cameraIdList.isEmpty()) {
                return Result.failure(Exception("未找到相机"))
            }

            val cameraId = cameraIdList.firstOrNull { id ->
                try {
                    cameraManager.getCameraCharacteristics(id)
                        .get(CameraCharacteristics.LENS_FACING) ==
                        CameraCharacteristics.LENS_FACING_BACK
                } catch (e: Exception) {
                    Log.w(TAG, "Error checking camera $id", e)
                    false
                }
            } ?: cameraIdList.first()

            Log.d(TAG, "Selected camera: $cameraId")
            val characteristics = cameraManager.getCameraCharacteristics(cameraId)

            startBackgroundThread()
            awaitingStillJpeg = false
            val photoResult = CompletableDeferred<ByteArray>()

            try {
                cameraManager.openCamera(
                    cameraId,
                    object : CameraDevice.StateCallback() {
                        override fun onOpened(camera: CameraDevice) {
                            Log.d(TAG, "Camera opened")
                            cameraDevice = camera
                            startCaptureSession(camera, characteristics, photoResult)
                        }

                        override fun onDisconnected(camera: CameraDevice) {
                            Log.w(TAG, "Camera disconnected")
                            camera.close()
                            photoResult.completeExceptionally(Exception("相机已断开"))
                        }

                        override fun onError(camera: CameraDevice, error: Int) {
                            Log.e(TAG, "Camera error: $error")
                            camera.close()
                            photoResult.completeExceptionally(Exception("相机错误: $error"))
                        }
                    },
                    backgroundHandler
                )
            } catch (e: Exception) {
                Log.e(TAG, "Error opening camera", e)
                return Result.failure(e)
            }

            return try {
                val jpeg = withTimeout(CAPTURE_TIMEOUT_MS) { photoResult.await() }
                Log.i(TAG, "Capture OK: ${jpeg.size} bytes")
                Result.success(jpeg)
            } catch (e: Exception) {
                Log.e(TAG, "Capture failed", e)
                Result.failure(e)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Capture error", e)
            return Result.failure(e)
        } finally {
            releaseCameraAndThread()
        }
    }

    private fun startCaptureSession(
        camera: CameraDevice,
        characteristics: CameraCharacteristics,
        photoResult: CompletableDeferred<ByteArray>
    ) {
        try {
            val streamConfigMap =
                characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            val outputSizes = streamConfigMap?.getOutputSizes(ImageFormat.JPEG) ?: emptyArray()
            val captureSize = chooseJpegSize(outputSizes)
            Log.d(TAG, "JPEG capture size: ${captureSize.width}x${captureSize.height}")

            imageReader = ImageReader.newInstance(
                captureSize.width,
                captureSize.height,
                ImageFormat.JPEG,
                2
            ).apply {
                setOnImageAvailableListener(
                    { reader ->
                        val image = reader.acquireNextImage() ?: return@setOnImageAvailableListener
                        try {
                            if (!awaitingStillJpeg) {
                                Log.d(TAG, "Discarding warmup/preview JPEG frame")
                                return@setOnImageAvailableListener
                            }
                            val buffer = image.planes[0].buffer
                            val bytes = ByteArray(buffer.remaining())
                            buffer.get(bytes)
                            if (!isValidJpeg(bytes)) {
                                Log.w(TAG, "Invalid JPEG frame: ${bytes.size} bytes")
                                if (!photoResult.isCompleted) {
                                    photoResult.completeExceptionally(
                                        Exception("无效 JPEG（${bytes.size} bytes）")
                                    )
                                }
                            } else if (!photoResult.isCompleted) {
                                Log.d(TAG, "JPEG received: ${bytes.size} bytes")
                                photoResult.complete(bytes)
                            }
                        } catch (e: Exception) {
                            Log.e(TAG, "Error reading image", e)
                            if (!photoResult.isCompleted) {
                                photoResult.completeExceptionally(e)
                            }
                        } finally {
                            image.close()
                        }
                    },
                    backgroundHandler
                )
            }

            camera.createCaptureSession(
                listOf(imageReader!!.surface),
                object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(session: CameraCaptureSession) {
                        Log.d(TAG, "Capture session configured, starting warmup")
                        captureSession = session
                        startPreviewWarmup(session, characteristics, photoResult)
                    }

                    override fun onConfigureFailed(session: CameraCaptureSession) {
                        Log.e(TAG, "Capture session configure failed")
                        photoResult.completeExceptionally(Exception("相机会话配置失败"))
                    }
                },
                backgroundHandler
            )
        } catch (e: Exception) {
            Log.e(TAG, "Error starting capture session", e)
            photoResult.completeExceptionally(e)
        }
    }

    private fun startPreviewWarmup(
        session: CameraCaptureSession,
        characteristics: CameraCharacteristics,
        photoResult: CompletableDeferred<ByteArray>
    ) {
        val handler = backgroundHandler ?: return
        val warmupStartMs = SystemClock.elapsedRealtime()
        var previewFrames = 0
        var stillTriggered = false

        fun triggerStillOnce(reason: String) {
            if (stillTriggered || photoResult.isCompleted) return
            stillTriggered = true
            warmupTimeoutRunnable?.let { handler.removeCallbacks(it) }
            Log.i(TAG, "Triggering still capture: $reason (after $previewFrames preview frames)")
            triggerStillCapture(session, photoResult)
        }

        val afModes = characteristics.get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES) ?: intArrayOf()
        val supportsContinuousAf = afModes.contains(CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)

        val previewBuilder = session.device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
            addTarget(imageReader!!.surface)
            set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
            if (supportsContinuousAf) {
                set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
            } else if (afModes.contains(CaptureRequest.CONTROL_AF_MODE_AUTO)) {
                set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_AUTO)
            }
            set(CaptureRequest.CONTROL_AWB_MODE, CaptureRequest.CONTROL_AWB_MODE_AUTO)
            set(CaptureRequest.JPEG_QUALITY, 90.toByte())
        }

        warmupTimeoutRunnable = Runnable {
            triggerStillOnce("warmup timeout ${WARMUP_TIMEOUT_MS}ms")
        }
        handler.postDelayed(warmupTimeoutRunnable!!, WARMUP_TIMEOUT_MS)

        try {
            session.setRepeatingRequest(
                previewBuilder.build(),
                object : CameraCaptureSession.CaptureCallback() {
                    override fun onCaptureCompleted(
                        session: CameraCaptureSession,
                        request: CaptureRequest,
                        result: TotalCaptureResult
                    ) {
                        if (stillTriggered || photoResult.isCompleted) return

                        previewFrames++
                        val aeState = result.get(CaptureResult.CONTROL_AE_STATE)
                        val afState = result.get(CaptureResult.CONTROL_AF_STATE)
                        val aeReady = aeState == CaptureResult.CONTROL_AE_STATE_CONVERGED
                            || aeState == CaptureResult.CONTROL_AE_STATE_LOCKED
                        val afReady = afState == CaptureResult.CONTROL_AF_STATE_FOCUSED_LOCKED
                            || afState == CaptureResult.CONTROL_AF_STATE_PASSIVE_FOCUSED
                            || !supportsContinuousAf

                        if (previewFrames >= MIN_PREVIEW_FRAMES && aeReady && afReady) {
                            triggerStillOnce("AE/AF converged at frame $previewFrames")
                            return
                        }

                        if (previewFrames >= MAX_WARMUP_FRAMES) {
                            triggerStillOnce("max warmup frames ($previewFrames)")
                            return
                        }

                        val elapsed = SystemClock.elapsedRealtime() - warmupStartMs
                        if (previewFrames % 3 == 0) {
                            Log.d(
                                TAG,
                                "Warmup frame $previewFrames: ae=$aeState af=$afState elapsed=${elapsed}ms"
                            )
                        }
                    }

                    override fun onCaptureFailed(
                        session: CameraCaptureSession,
                        request: CaptureRequest,
                        failure: android.hardware.camera2.CaptureFailure
                    ) {
                        Log.w(TAG, "Preview frame failed: ${failure.reason}")
                    }
                },
                handler
            )
        } catch (e: Exception) {
            Log.e(TAG, "Preview warmup failed, fallback to direct capture", e)
            triggerStillOnce("preview error fallback")
        }
    }

    private fun triggerStillCapture(
        session: CameraCaptureSession,
        photoResult: CompletableDeferred<ByteArray>
    ) {
        try {
            try {
                session.stopRepeating()
                session.abortCaptures()
            } catch (e: Exception) {
                Log.w(TAG, "Stop repeating before still capture", e)
            }

            awaitingStillJpeg = true

            val builder = session.device.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE).apply {
                addTarget(imageReader!!.surface)
                set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
                set(CaptureRequest.CONTROL_AWB_MODE, CaptureRequest.CONTROL_AWB_MODE_AUTO)
                set(CaptureRequest.JPEG_QUALITY, 92.toByte())
            }

            session.capture(
                builder.build(),
                object : CameraCaptureSession.CaptureCallback() {
                    override fun onCaptureCompleted(
                        session: CameraCaptureSession,
                        request: CaptureRequest,
                        result: TotalCaptureResult
                    ) {
                        Log.d(TAG, "Still capture completed")
                    }

                    override fun onCaptureFailed(
                        session: CameraCaptureSession,
                        request: CaptureRequest,
                        failure: android.hardware.camera2.CaptureFailure
                    ) {
                        Log.e(TAG, "Still capture failed: ${failure.reason}")
                        if (!photoResult.isCompleted) {
                            photoResult.completeExceptionally(Exception("静态拍照失败"))
                        }
                    }
                },
                backgroundHandler
            )
        } catch (e: Exception) {
            Log.e(TAG, "Error triggering still capture", e)
            if (!photoResult.isCompleted) {
                photoResult.completeExceptionally(e)
            }
        }
    }

    /** 优先 1280 以内分辨率：画质够用且预热/传图更快 */
    private fun chooseJpegSize(sizes: Array<Size>): Size {
        if (sizes.isEmpty()) return Size(1280, 720)
        val sorted = sizes.sortedByDescending { it.width * it.height }
        return sorted.firstOrNull { it.width <= 1280 && it.height <= 960 }
            ?: sorted.firstOrNull { it.width <= 1920 && it.height <= 1080 }
            ?: sorted.last()
    }

    private fun isValidJpeg(bytes: ByteArray): Boolean {
        if (bytes.size < 100) return false
        return bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte()
    }

    private fun startBackgroundThread() {
        backgroundThread = HandlerThread("CameraBackground").apply {
            start()
            backgroundHandler = Handler(looper)
        }
    }

    private fun stopBackgroundThread() {
        backgroundThread?.quitSafely()
        try {
            backgroundThread?.join()
        } catch (e: InterruptedException) {
            Log.e(TAG, "Error stopping background thread", e)
        }
        backgroundThread = null
        backgroundHandler = null
    }

    private suspend fun releaseCameraAndThread() {
        awaitingStillJpeg = false
        warmupTimeoutRunnable?.let { backgroundHandler?.removeCallbacks(it) }
        warmupTimeoutRunnable = null
        try {
            captureSession?.stopRepeating()
            captureSession?.abortCaptures()
            captureSession?.close()
        } catch (e: Exception) {
            Log.w(TAG, "Error closing session", e)
        }
        captureSession = null

        imageReader?.close()
        imageReader = null

        try {
            cameraDevice?.close()
        } catch (e: Exception) {
            Log.w(TAG, "Error closing camera device", e)
        }
        cameraDevice = null

        delay(400)
        stopBackgroundThread()
    }
}
