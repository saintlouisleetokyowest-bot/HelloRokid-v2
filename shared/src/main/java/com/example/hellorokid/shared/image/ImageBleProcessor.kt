package com.example.hellorokid.shared.image

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.util.Log
import com.example.hellorokid.shared.camera.CameraCropMapper
import java.io.ByteArrayOutputStream

/**
 * BLE 传图前处理：旋转、取景裁切、彩色 JPEG（OCR 优先，避免灰度/过度压缩）。
 */
object ImageBleProcessor {

    private const val TAG = "ImageBleProcessor"
    /** 裁切后名片图通常已较小，尽量保持原分辨率 */
    private const val MAX_WIDTH_CROPPED = 1600
    private const val MAX_WIDTH_FALLBACK = 1280
    private const val MIN_SHORT_EDGE_UPSCALE = 640
    private const val JPEG_QUALITY_OCR = 92
    /** Rokid 横拍 JPEG 转竖直握持：+270° */
    private const val ROKID_LANDSCAPE_TO_PORTRAIT = 270f

    data class ProcessResult(
        val jpegBytes: ByteArray,
        val originalSize: Int,
        val outputSize: Int,
        val width: Int,
        val height: Int
    )

    fun prepareForBleTransfer(
        jpegBytes: ByteArray,
        cropRect: NormalizedRect? = null,
        /** 与预览一致的顺时针旋转角（通常 = sensorOrientation - displayRotation） */
        outputRotationDegrees: Int? = null
    ): ProcessResult {
        val cropped = cropRect != null
        var bitmap = decodeRawJpeg(jpegBytes)
            ?: return ProcessResult(jpegBytes, jpegBytes.size, jpegBytes.size, 0, 0)

        val sourceLandscape = bitmap.width > bitmap.height
        val rotateDegrees = resolveOutputRotation(sourceLandscape, outputRotationDegrees)

        // 先旋转到 HUD 方向，再按取景框裁切（cropRect 已在旋转后坐标系）
        if (rotateDegrees != 0) {
            val rotated = rotate(bitmap, rotateDegrees.toFloat())
            if (rotated !== bitmap) bitmap.recycle()
            bitmap = rotated
            Log.d(TAG, "Pre-rotate ${rotateDegrees}° before crop (sourceLandscape=$sourceLandscape)")
        }

        if (cropRect != null) {
            val bounds = CameraCropMapper.cropRectToPixelBounds(cropRect, bitmap.width, bitmap.height)
            val cut = Bitmap.createBitmap(
                bitmap,
                bounds.left,
                bounds.top,
                bounds.width(),
                bounds.height()
            )
            if (cut !== bitmap) bitmap.recycle()
            bitmap = cut
            Log.d(TAG, "Cropped in HUD orientation: ${bitmap.width}x${bitmap.height} bounds=$bounds")
        }

        if (cropped) {
            val shortEdge = minOf(bitmap.width, bitmap.height)
            if (shortEdge < MIN_SHORT_EDGE_UPSCALE) {
                val upscaled = upscaleShortEdge(bitmap, MIN_SHORT_EDGE_UPSCALE)
                if (upscaled !== bitmap) bitmap.recycle()
                bitmap = upscaled
            }
        }

        val maxWidth = if (cropped) MAX_WIDTH_CROPPED else MAX_WIDTH_FALLBACK
        val scaled = scaleToMaxWidth(bitmap, maxWidth)
        if (scaled !== bitmap) {
            bitmap.recycle()
            bitmap = scaled
        }

        val output = compressJpeg(bitmap, JPEG_QUALITY_OCR)
        val w = bitmap.width
        val h = bitmap.height
        bitmap.recycle()

        Log.i(
            TAG,
            "BLE OCR image: ${jpegBytes.size}B -> ${output.size}B (${w}x$h color q$JPEG_QUALITY_OCR, cropped=$cropped)"
        )

        return ProcessResult(
            jpegBytes = output,
            originalSize = jpegBytes.size,
            outputSize = output.size,
            width = w,
            height = h
        )
    }

    /** 按相机输出方向解码，不应用 EXIF 旋转（裁切坐标基于此坐标系） */
    private fun decodeRawJpeg(jpegBytes: ByteArray): Bitmap? {
        return BitmapFactory.decodeByteArray(jpegBytes, 0, jpegBytes.size)
    }

    /**
     * 裁切坐标已在旋转后（HUD）方向；仅对原始横屏 JPEG 做预旋转。
     */
    private fun resolveOutputRotation(
        sourceLandscape: Boolean,
        outputRotationDegrees: Int?
    ): Int {
        if (!sourceLandscape) return 0
        return outputRotationDegrees ?: ROKID_LANDSCAPE_TO_PORTRAIT.toInt()
    }

    private fun upscaleShortEdge(bitmap: Bitmap, minShort: Int): Bitmap {
        val short = minOf(bitmap.width, bitmap.height)
        if (short >= minShort) return bitmap
        val scale = minShort.toFloat() / short
        val nw = (bitmap.width * scale).toInt().coerceAtLeast(1)
        val nh = (bitmap.height * scale).toInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(bitmap, nw, nh, true)
    }

    private fun rotate(bitmap: Bitmap, degrees: Float): Bitmap {
        val matrix = Matrix().apply { postRotate(degrees) }
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
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
