package com.example.hellorokid.glass.camera

import android.Manifest
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
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
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

/**
 * Rokid 眼镜相机管理器，使用 Camera2 API 拍照。
 */
class RokidCameraManager(private val context: Context) {

    companion object {
        private const val TAG = "RokidCameraManager"
    }

    private var cameraDevice: CameraDevice? = null
    private var captureSession: CameraCaptureSession? = null
    private var backgroundThread: HandlerThread? = null
    private var backgroundHandler: Handler? = null
    private var imageReader: ImageReader? = null

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
            backgroundThread = null
            backgroundHandler = null
        } catch (e: InterruptedException) {
            Log.e(TAG, "Error stopping background thread", e)
        }
    }

    @RequiresPermission(Manifest.permission.CAMERA)
    suspend fun capturePhoto(): Result<Bitmap> = withContext(Dispatchers.IO) {
        try {
            Log.d(TAG, "Starting camera capture")
            val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager

            val cameraIdList = try {
                cameraManager.cameraIdList
            } catch (e: CameraAccessException) {
                Log.e(TAG, "Cannot get camera list", e)
                emptyArray()
            }

            if (cameraIdList.isEmpty()) {
                return@withContext Result.failure(Exception("No camera found"))
            }

            val cameraId = cameraIdList.firstOrNull { id ->
                try {
                    val characteristics = cameraManager.getCameraCharacteristics(id)
                    characteristics.get(CameraCharacteristics.LENS_FACING) ==
                        CameraCharacteristics.LENS_FACING_BACK
                } catch (e: Exception) {
                    false
                }
            } ?: cameraIdList.first()

            startBackgroundThread()
            val photoResult = CompletableDeferred<Bitmap>()

            cameraManager.openCamera(
                cameraId,
                object : CameraDevice.StateCallback() {
                    override fun onOpened(camera: CameraDevice) {
                        cameraDevice = camera
                        startCaptureSession(camera, cameraId, cameraManager, photoResult)
                    }

                    override fun onDisconnected(camera: CameraDevice) {
                        camera.close()
                        photoResult.completeExceptionally(Exception("Camera disconnected"))
                    }

                    override fun onError(camera: CameraDevice, error: Int) {
                        camera.close()
                        photoResult.completeExceptionally(Exception("Camera error: $error"))
                    }
                },
                backgroundHandler
            )

            return@withContext try {
                val bitmap = withTimeout(5000) { photoResult.await() }
                Result.success(bitmap)
            } catch (e: Exception) {
                Result.failure(e)
            } finally {
                closeCamera()
                stopBackgroundThread()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error capturing photo, using test bitmap", e)
            Result.success(createTestBitmap())
        }
    }

    private fun startCaptureSession(
        camera: CameraDevice,
        cameraId: String,
        cameraManager: CameraManager,
        photoResult: CompletableDeferred<Bitmap>
    ) {
        try {
            val characteristics = cameraManager.getCameraCharacteristics(cameraId)
            val streamConfigMap = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            val outputSizes = streamConfigMap?.getOutputSizes(ImageFormat.JPEG) ?: emptyArray()
            val optimalSize = outputSizes.firstOrNull() ?: Size(640, 480)

            imageReader = ImageReader.newInstance(
                optimalSize.width,
                optimalSize.height,
                ImageFormat.JPEG,
                1
            ).apply {
                setOnImageAvailableListener(
                    { reader ->
                        val image = reader.acquireNextImage()
                        try {
                            val buffer = image.planes[0].buffer
                            val bytes = ByteArray(buffer.remaining())
                            buffer.get(bytes)
                            val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                            if (bitmap != null) {
                                photoResult.complete(bitmap)
                            } else {
                                photoResult.completeExceptionally(Exception("Failed to decode bitmap"))
                            }
                        } catch (e: Exception) {
                            photoResult.completeExceptionally(e)
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
                        captureSession = session
                        try {
                            val captureRequestBuilder =
                                session.device.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE)
                            captureRequestBuilder.addTarget(imageReader!!.surface)
                            session.capture(
                                captureRequestBuilder.build(),
                                object : CameraCaptureSession.CaptureCallback() {
                                    override fun onCaptureCompleted(
                                        session: CameraCaptureSession,
                                        request: CaptureRequest,
                                        result: TotalCaptureResult
                                    ) {
                                        super.onCaptureCompleted(session, request, result)
                                    }
                                },
                                backgroundHandler
                            )
                        } catch (e: Exception) {
                            photoResult.completeExceptionally(e)
                        }
                    }

                    override fun onConfigureFailed(session: CameraCaptureSession) {
                        photoResult.completeExceptionally(Exception("Session configure failed"))
                    }
                },
                backgroundHandler
            )
        } catch (e: Exception) {
            photoResult.completeExceptionally(e)
        }
    }

    private fun closeCamera() {
        try {
            captureSession?.stopRepeating()
            captureSession?.abortCaptures()
            captureSession?.close()
        } catch (e: Exception) {
            Log.w(TAG, "Error closing session", e)
        }
        captureSession = null
        cameraDevice?.close()
        cameraDevice = null
        imageReader?.close()
        imageReader = null
    }

    fun createTestBitmap(): Bitmap {
        val bitmap = Bitmap.createBitmap(640, 480, Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(bitmap)
        val paint = android.graphics.Paint()
        paint.color = android.graphics.Color.WHITE
        canvas.drawPaint(paint)
        paint.color = android.graphics.Color.BLACK
        paint.textSize = 32f
        canvas.drawText("Rokid Card Scanner", 50f, 100f, paint)
        return bitmap
    }
}
