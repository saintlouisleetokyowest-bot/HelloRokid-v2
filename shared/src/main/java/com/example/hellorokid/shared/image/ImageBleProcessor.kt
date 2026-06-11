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
 * BLE 传图前处理：纠正方向、提亮、转灰度、缩小体积（名片 OCR 不需要彩色）。
 */
object ImageBleProcessor {

    private const val TAG = "ImageBleProcessor"
    private const val MAX_WIDTH = 800
    private const val JPEG_QUALITY = 70
    /** Rokid 横拍 JPEG 转竖直握持：+90° 会上下颠倒，需 +270°（等同逆时针 90°） */
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

        // Rokid 横拍 JPEG（如 1920×1080）需转为竖图；+270° 才是正向，+90° 会 180° 颠倒
        if (bitmap.width > bitmap.height) {
            val rotated = rotate(bitmap, ROKID_LANDSCAPE_TO_PORTRAIT)
            if (rotated !== bitmap) {
                bitmap.recycle()
            }
            bitmap = rotated
            Log.d(TAG, "Landscape -> portrait: rotated ${ROKID_LANDSCAPE_TO_PORTRAIT}°")
        }

        val brightened = boostBrightness(bitmap)
        if (brightened !== bitmap) {
            bitmap.recycle()
            bitmap = brightened
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

    private fun boostBrightness(bitmap: Bitmap): Bitmap {
        val brightness = estimateBrightness(bitmap)
        if (brightness >= 100) return bitmap

        val scale = (200f / brightness.coerceAtLeast(25f)).coerceIn(1.3f, 3.0f)
        val offset = 40f
        val matrix = ColorMatrix(
            floatArrayOf(
                scale, 0f, 0f, 0f, offset,
                0f, scale, 0f, 0f, offset,
                0f, 0f, scale, 0f, offset,
                0f, 0f, 0f, 1f, 0f
            )
        )
        return applyMatrix(bitmap, matrix)
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

    private fun estimateBrightness(bitmap: Bitmap): Float {
        val sample = Bitmap.createScaledBitmap(bitmap, 24, 24, true)
        val pixels = IntArray(24 * 24)
        sample.getPixels(pixels, 0, 24, 0, 0, 24, 24)
        if (sample !== bitmap) sample.recycle()
        var sum = 0L
        for (pixel in pixels) {
            val r = (pixel shr 16) and 0xFF
            val g = (pixel shr 8) and 0xFF
            val b = pixel and 0xFF
            sum += (0.299 * r + 0.587 * g + 0.114 * b).toLong()
        }
        return sum.toFloat() / pixels.size
    }
}
