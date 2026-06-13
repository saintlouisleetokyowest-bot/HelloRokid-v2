package com.example.hellorokid.shared.image

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.util.Log

/**
 * 拍照后处理：暗光增强 + 对比度提升，改善 OCR / AI 识别率。
 */
object ImagePostProcessor {

    private const val TAG = "ImagePostProcessor"
    private const val BRIGHT_THRESHOLD = 110

    fun enhanceForOcr(bitmap: Bitmap): Bitmap {
        if (bitmap.width <= 0 || bitmap.height <= 0) return bitmap

        val brightness = estimateBrightness(bitmap)
        val contrast = estimateContrast(bitmap)
        Log.d(TAG, "before: brightness=$brightness contrast=$contrast")

        if (brightness >= BRIGHT_THRESHOLD && contrast < 8) {
            return bitmap
        }

        val boosted = boostBrightness(bitmap, brightness)
        val sharpened = boostContrast(boosted, contrast)
        if (boosted !== bitmap && sharpened !== boosted) {
            boosted.recycle()
        }
        return sharpened
    }

    private fun boostBrightness(bitmap: Bitmap, brightness: Float): Bitmap {
        val (scale, offset) = when {
            brightness < 30 -> 3.2f to 75f
            brightness < 50 -> 2.6f to 55f
            brightness < 70 -> 2.0f to 40f
            else -> 1.5f to 25f
        }

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

    private fun boostContrast(bitmap: Bitmap, contrast: Int): Bitmap {
        val factor = when {
            contrast < 25 -> 1.45f
            contrast < 45 -> 1.25f
            else -> 1.1f
        }
        val translate = (-0.5f * factor + 0.5f) * 255f
        val matrix = ColorMatrix(
            floatArrayOf(
                factor, 0f, 0f, 0f, translate,
                0f, factor, 0f, 0f, translate,
                0f, 0f, factor, 0f, translate,
                0f, 0f, 0f, 1f, 0f
            )
        )
        val result = applyMatrix(bitmap, matrix)
        if (result !== bitmap) {
            Log.d(TAG, "after enhance: contrast factor=$factor")
        }
        return result
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

    private fun estimateBrightness(bitmap: Bitmap): Float {
        val sampleSize = 32
        val scaled = Bitmap.createScaledBitmap(bitmap, sampleSize, sampleSize, true)
        val pixels = IntArray(sampleSize * sampleSize)
        scaled.getPixels(pixels, 0, sampleSize, 0, 0, sampleSize, sampleSize)
        if (scaled !== bitmap) {
            scaled.recycle()
        }
        var sum = 0L
        for (pixel in pixels) {
            val r = (pixel shr 16) and 0xFF
            val g = (pixel shr 8) and 0xFF
            val b = pixel and 0xFF
            sum += (0.299 * r + 0.587 * g + 0.114 * b).toLong()
        }
        return sum.toFloat() / pixels.size
    }

    private fun estimateContrast(bitmap: Bitmap): Int {
        val sampleSize = 16
        val scaled = Bitmap.createScaledBitmap(bitmap, sampleSize, sampleSize, true)
        val pixels = IntArray(sampleSize * sampleSize)
        scaled.getPixels(pixels, 0, sampleSize, 0, 0, sampleSize, sampleSize)
        if (scaled !== bitmap) {
            scaled.recycle()
        }
        var minL = 255
        var maxL = 0
        for (pixel in pixels) {
            val r = (pixel shr 16) and 0xFF
            val g = (pixel shr 8) and 0xFF
            val b = pixel and 0xFF
            val l = (0.299 * r + 0.587 * g + 0.114 * b).toInt()
            minL = minOf(minL, l)
            maxL = maxOf(maxL, l)
        }
        return maxL - minL
    }
}
