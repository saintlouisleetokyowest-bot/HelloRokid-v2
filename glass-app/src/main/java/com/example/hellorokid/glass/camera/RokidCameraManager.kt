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
import android.hardware.camera2.TotalCaptureResult
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.util.Size
import androidx.annotation.RequiresPermission
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

/**
 * Rokid 眼镜相机：对齐 v1 的最简 Camera2 单次拍照（不做 AE 预捕获，避免 Rokid 回调卡死）。
 * 旋转、提亮、灰度压缩在 [com.example.hellorokid.shared.image.ImageBleProcessor] 中处理。
 */
class RokidCameraManager(private val context: Context) {

    companion object {
        private const val TAG = "RokidCameraManager"
        private const val CAPTURE_TIMEOUT_MS = 10_000L
        private const val MAX_ATTEMPTS = 2
    }

    private var cameraDevice: CameraDevice? = null
    private var captureSession: CameraCaptureSession? = null
    private var backgroundThread: HandlerThread? = null
    private var backgroundHandler: Handler? = null
    private var imageReader: ImageReader? = null

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

            Log.d(TAG, "Available cameras: ${cameraIdList.joinToString()}")

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

            startBackgroundThread()
            val photoResult = CompletableDeferred<ByteArray>()

            try {
                cameraManager.openCamera(
                    cameraId,
                    object : CameraDevice.StateCallback() {
                        override fun onOpened(camera: CameraDevice) {
                            Log.d(TAG, "Camera opened")
                            cameraDevice = camera
                            startCaptureSession(camera, cameraId, cameraManager, photoResult)
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
        cameraId: String,
        cameraManager: CameraManager,
        photoResult: CompletableDeferred<ByteArray>
    ) {
        try {
            val characteristics = cameraManager.getCameraCharacteristics(cameraId)
            val streamConfigMap =
                characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            val outputSizes = streamConfigMap?.getOutputSizes(ImageFormat.JPEG) ?: emptyArray()
            val optimalSize = chooseJpegSize(outputSizes)
            Log.d(TAG, "JPEG size: ${optimalSize.width}x${optimalSize.height}")

            imageReader = ImageReader.newInstance(
                optimalSize.width,
                optimalSize.height,
                ImageFormat.JPEG,
                2
            ).apply {
                setOnImageAvailableListener(
                    { reader ->
                        val image = reader.acquireNextImage() ?: return@setOnImageAvailableListener
                        try {
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
                        Log.d(TAG, "Capture session configured")
                        captureSession = session
                        triggerStillCapture(session, photoResult)
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

    /**
     * 与 v1 一致：仅 STILL_CAPTURE + target，不设置 AE/曝光补偿/Orientation（Rokid 上易卡死）。
     */
    private fun triggerStillCapture(
        session: CameraCaptureSession,
        photoResult: CompletableDeferred<ByteArray>
    ) {
        try {
            val builder = session.device.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE)
            builder.addTarget(imageReader!!.surface)

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
                },
                backgroundHandler
            )
        } catch (e: Exception) {
            Log.e(TAG, "Error triggering capture", e)
            photoResult.completeExceptionally(e)
        }
    }

    /** 优先选中等分辨率，避免过大尺寸在 Rokid 上过慢 */
    private fun chooseJpegSize(sizes: Array<Size>): Size {
        if (sizes.isEmpty()) return Size(640, 480)
        val sorted = sizes.sortedByDescending { it.width * it.height }
        return sorted.firstOrNull { it.width <= 1920 && it.height <= 1080 }
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

    /** 关闭相机后稍等再停后台线程，避免 Camera2 回调打到 dead thread（minSdk 24 无 close(callback)） */
    private suspend fun releaseCameraAndThread() {
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
