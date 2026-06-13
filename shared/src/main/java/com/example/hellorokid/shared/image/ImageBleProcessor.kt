package com.example.hellorokid.shared.image

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Matrix
import android.graphics.Paint
import android.util.Log
import androidx.exifinterface.media.ExifInterface
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

/**
 * BLE 传图前处理：纠正方向、转灰度、适度缩小压缩（单次 JPEG 编码，避免二次损伤）。
 */
object ImageBleProcessor {

    private const val TAG = "ImageBleProcessor"
    private const val MAX_WIDTH = 960
    private const val JPEG_QUALITY = 75
    /** Rokid 横拍 JPEG 转竖直握持：+270°（等同逆时针 90°） */
    private const val ROKID_LANDSCAPE_TO_PORTRAIT = 270f

    data class ProcessResult(
        val jpegBytes: ByteArray,
        val originalSize: Int,
        val outputSize: Int,
        val width: Int,
        val height: Int
    )

    fun prepareForBleTransfer(jpegBytes: ByteArray): ProcessResult {
        var bitmap = decodeWithExifRotation(jpegBytes)
            ?: return ProcessResult(jpegBytes, jpegBytes.size, jpegBytes.size, 0, 0)

        if (bitmap.width > bitmap.height) {
            val rotated = rotate(bitmap, ROKID_LANDSCAPE_TO_PORTRAIT)
            if (rotated !== bitmap) {
                bitmap.recycle()
            }
            bitmap = rotated
            Log.d(TAG, "Landscape -> portrait: rotated ${ROKID_LANDSCAPE_TO_PORTRAIT}°")
        }

        val gray = toGrayscale(bitmap)
        if (gray !== bitmap) {
            bitmap.recycle()
            bitmap = gray
        }

        val scaled = scaleToMaxWidth(bitmap, MAX_WIDTH)
        if (scaled !== bitmap) {
            bitmap.recycle()
            bitmap = scaled
        }

        val output = compressJpeg(bitmap, JPEG_QUALITY)
        val w = bitmap.width
        val h = bitmap.height
        bitmap.recycle()

        Log.i(
            TAG,
            "BLE image: ${jpegBytes.size}B -> ${output.size}B (${w}x$h gray q$JPEG_QUALITY)"
        )

        return ProcessResult(
            jpegBytes = output,
            originalSize = jpegBytes.size,
            outputSize = output.size,
            width = w,
            height = h
        )
    }

    private fun decodeWithExifRotation(jpegBytes: ByteArray): Bitmap? {
        var bitmap = BitmapFactory.decodeByteArray(jpegBytes, 0, jpegBytes.size) ?: return null
        val exif = ExifInterface(ByteArrayInputStream(jpegBytes))
        val orientation = exif.getAttributeInt(
            ExifInterface.TAG_ORIENTATION,
            ExifInterface.ORIENTATION_NORMAL
        )
        val degrees = when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> 90f
            ExifInterface.ORIENTATION_ROTATE_180 -> 180f
            ExifInterface.ORIENTATION_ROTATE_270 -> 270f
            else -> 0f
        }
        if (degrees != 0f) {
            val rotated = rotate(bitmap, degrees)
            if (rotated !== bitmap) {
                bitmap.recycle()
            }
            bitmap = rotated
        }
        return bitmap
    }

    private fun rotate(bitmap: Bitmap, degrees: Float): Bitmap {
        val matrix = Matrix().apply { postRotate(degrees) }
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
    }

    private fun toGrayscale(bitmap: Bitmap): Bitmap {
        val matrix = ColorMatrix().apply { setSaturation(0f) }
        return applyMatrix(bitmap, matrix)
    }

    private fun applyMatrix(bitmap: Bitmap, matrix: ColorMatrix): Bitmap {
        val result = Bitmap.createBitmap(bitmap.width, bitmap.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(result)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            colorFilter = ColorMatrixColorFilter(matrix)
        }
        canvas.drawBitmap(bitmap, 0f, 0f, paint)
        return result
    }

    private fun scaleToMaxWidth(bitmap: Bitmap, maxWidth: Int): Bitmap {
        if (bitmap.width <= maxWidth) return bitmap
        val ratio = maxWidth.toFloat() / bitmap.width
        val h = (bitmap.height * ratio).toInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(bitmap, maxWidth, h, true)
    }

    private fun compressJpeg(bitmap: Bitmap, quality: Int): ByteArray {
        val stream = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, quality, stream)
        return stream.toByteArray()
    }
}
