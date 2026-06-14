package com.example.hellorokid.shared.camera

import android.graphics.Matrix
import android.graphics.RectF
import android.util.Log
import android.util.Size
import android.view.Surface
import android.view.TextureView
import com.example.hellorokid.shared.image.NormalizedRect
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max

/**
 * 取景框 → 成片裁切映射。
 *
 * 与 [buildPreviewTransform] / [FramingGeometry] 使用同一套 center-crop 逻辑：
 * View 归一化绿框 → 旋转后 preview 归一化 → 旋转后 still 归一化。
 */
object CameraCropMapper {

    private const val TAG = "CameraCropMapper"

    data class PreviewTransform(
        val matrix: Matrix,
        val rotationDegrees: Int,
        val previewSize: Size
    )

    fun displayRotationToDegrees(displayRotation: Int): Int = when (displayRotation) {
        Surface.ROTATION_90 -> 90
        Surface.ROTATION_180 -> 180
        Surface.ROTATION_270 -> 270
        else -> 0
    }

    fun computePreviewRotationDegrees(displayRotation: Int, sensorOrientation: Int): Int {
        return (sensorOrientation - displayRotationToDegrees(displayRotation) + 360) % 360
    }

    /** TextureView 变换：buffer 坐标 → view 坐标（与 setTransform 一致） */
    fun buildPreviewTransform(
        viewWidth: Int,
        viewHeight: Int,
        previewSize: Size,
        displayRotation: Int,
        sensorOrientation: Int
    ): PreviewTransform {
        val rotation = computePreviewRotationDegrees(displayRotation, sensorOrientation)
        val matrix = Matrix()
        if (viewWidth == 0 || viewHeight == 0) {
            return PreviewTransform(matrix, rotation, previewSize)
        }

        val cx = viewWidth / 2f
        val cy = viewHeight / 2f
        val rotatedPreviewW = rotatedWidth(previewSize, rotation)
        val rotatedPreviewH = rotatedHeight(previewSize, rotation)
        val scale = max(viewWidth / rotatedPreviewW, viewHeight / rotatedPreviewH)

        matrix.setTranslate(cx, cy)
        matrix.postRotate(rotation.toFloat())
        matrix.postScale(scale, scale)
        matrix.postTranslate(-previewSize.width / 2f, -previewSize.height / 2f)

        return PreviewTransform(matrix, rotation, previewSize)
    }

    fun configureTransform(
        view: TextureView,
        previewSize: Size,
        displayRotation: Int,
        sensorOrientation: Int
    ): PreviewTransform {
        val transform = buildPreviewTransform(
            view.width,
            view.height,
            previewSize,
            displayRotation,
            sensorOrientation
        )
        view.setTransform(transform.matrix)
        return transform
    }

    /**
     * 取景框（View 归一化，与绿框一致）→ 旋转后 still 归一化裁切。
     */
    fun mapNormalizedViewRectToCapture(
        viewNorm: NormalizedRect,
        viewWidth: Int,
        viewHeight: Int,
        previewTransform: PreviewTransform,
        captureSize: Size
    ): NormalizedRect {
        if (viewWidth <= 0 || viewHeight <= 0) {
            return fallbackCrop()
        }

        val rotation = previewTransform.rotationDegrees
        val previewSize = previewTransform.previewSize

        val rotPreviewNorm = mapViewNormToRotPreviewNorm(
            viewNorm,
            viewWidth,
            viewHeight,
            previewSize,
            rotation
        )

        if (rotPreviewNorm.width() < 0.01f || rotPreviewNorm.height() < 0.01f) {
            Log.w(TAG, "Degenerate rotPreview=$rotPreviewNorm, using fallback crop")
            return fallbackCrop()
        }

        val captureNorm = mapRotPreviewNormToRotatedCaptureNorm(
            rotPreviewNorm,
            previewSize,
            captureSize,
            rotation
        )

        val result = NormalizedRect(
            captureNorm.left,
            captureNorm.top,
            captureNorm.right,
            captureNorm.bottom
        ).expandForCrop()

        Log.d(
            TAG,
            "Crop map: viewNorm=$viewNorm rotPreview=${rotPreviewNorm.toShortString()} " +
                "rotCapture=${captureNorm.toShortString()} out=$result " +
                "(preview=${previewSize.width}x${previewSize.height} " +
                "capture=${captureSize.width}x${captureSize.height} rot=${rotation}°)"
        )
        return result
    }

    /** 兼容像素 RectF 入口 */
    fun mapViewRectToCaptureNormalized(
        viewFraming: RectF,
        viewWidth: Int,
        viewHeight: Int,
        previewTransform: PreviewTransform,
        captureSize: Size
    ): NormalizedRect {
        if (viewWidth <= 0 || viewHeight <= 0) {
            return fallbackCrop()
        }
        val viewNorm = NormalizedRect(
            left = viewFraming.left / viewWidth,
            top = viewFraming.top / viewHeight,
            right = viewFraming.right / viewWidth,
            bottom = viewFraming.bottom / viewHeight
        )
        return mapNormalizedViewRectToCapture(
            viewNorm,
            viewWidth,
            viewHeight,
            previewTransform,
            captureSize
        )
    }

    /**
     * View 归一化 → 旋转后 preview 归一化。
     * 与 [buildPreviewTransform] 的 center-crop 互逆，不对中间坐标 clamp（避免左侧被切）。
     */
    private fun mapViewNormToRotPreviewNorm(
        viewNorm: NormalizedRect,
        viewWidth: Int,
        viewHeight: Int,
        previewSize: Size,
        rotation: Int
    ): RectF {
        val vw = viewWidth.toFloat()
        val vh = viewHeight.toFloat()
        val rotW = rotatedWidth(previewSize, rotation)
        val rotH = rotatedHeight(previewSize, rotation)
        val scale = max(vw / rotW, vh / rotH)
        val offX = (rotW - vw / scale) / 2f
        val offY = (rotH - vh / scale) / 2f

        fun toRotNorm(vx: Float, vy: Float): Pair<Float, Float> {
            val rpx = (offX + vx / scale) / rotW
            val rpy = (offY + vy / scale) / rotH
            return rpx to rpy
        }

        val corners = listOf(
            toRotNorm(viewNorm.left * vw, viewNorm.top * vh),
            toRotNorm(viewNorm.right * vw, viewNorm.top * vh),
            toRotNorm(viewNorm.right * vw, viewNorm.bottom * vh),
            toRotNorm(viewNorm.left * vw, viewNorm.bottom * vh)
        )

        return RectF(
            corners.minOf { it.first },
            corners.minOf { it.second },
            corners.maxOf { it.first },
            corners.maxOf { it.second }
        )
    }

    /**
     * 旋转后 preview 归一化 → 旋转后 still 归一化（center-crop 视场差补偿）。
     */
    private fun mapRotPreviewNormToRotatedCaptureNorm(
        rotPreviewNorm: RectF,
        previewSize: Size,
        captureSize: Size,
        rotation: Int
    ): RectF {
        val rotPreviewAspect = rotatedWidth(previewSize, rotation) / rotatedHeight(previewSize, rotation)
        val rotCaptureAspect = rotatedWidth(captureSize, rotation) / rotatedHeight(captureSize, rotation)

        return if (rotPreviewAspect > rotCaptureAspect) {
            val visibleHeight = rotCaptureAspect / rotPreviewAspect
            val padY = (1f - visibleHeight) / 2f
            RectF(
                rotPreviewNorm.left,
                padY + rotPreviewNorm.top * visibleHeight,
                rotPreviewNorm.right,
                padY + rotPreviewNorm.bottom * visibleHeight
            )
        } else {
            val visibleWidth = rotPreviewAspect / rotCaptureAspect
            val padX = (1f - visibleWidth) / 2f
            RectF(
                padX + rotPreviewNorm.left * visibleWidth,
                rotPreviewNorm.top,
                padX + rotPreviewNorm.right * visibleWidth,
                rotPreviewNorm.bottom
            )
        }
    }

    private fun rotatedWidth(size: Size, rotation: Int): Float {
        return if (rotation == 90 || rotation == 270) size.height.toFloat() else size.width.toFloat()
    }

    private fun rotatedHeight(size: Size, rotation: Int): Float {
        return if (rotation == 90 || rotation == 270) size.width.toFloat() else size.height.toFloat()
    }

    private fun fallbackCrop(): NormalizedRect = NormalizedRect(0.1f, 0.15f, 0.9f, 0.85f)

    private fun RectF.toShortString(): String =
        "(${String.format("%.2f", left)},${String.format("%.2f", top)}-" +
            "${String.format("%.2f", right)},${String.format("%.2f", bottom)})"

    private fun NormalizedRect.expandForCrop(): NormalizedRect {
        val l = (left - FramingGeometry.CROP_MARGIN_LEFT).coerceAtLeast(0f)
        val t = (top - FramingGeometry.CROP_MARGIN_VERTICAL).coerceAtLeast(0f)
        val r = (right + FramingGeometry.CROP_MARGIN_RIGHT).coerceAtMost(1f)
        val b = (bottom + FramingGeometry.CROP_MARGIN_VERTICAL).coerceAtMost(1f)
        return NormalizedRect(
            l,
            t,
            r.coerceAtLeast(l + 0.02f),
            b.coerceAtLeast(t + 0.02f)
        )
    }

    /** 裁切像素边界：含安全像素余量，左侧多加几像素防缺字 */
    fun cropRectToPixelBounds(rect: NormalizedRect, bitmapWidth: Int, bitmapHeight: Int): android.graphics.Rect {
        val padLeft = 5
        val padOther = 2
        val l = (floor(rect.left * bitmapWidth).toInt() - padLeft).coerceIn(0, bitmapWidth - 2)
        val t = (floor(rect.top * bitmapHeight).toInt() - padOther).coerceIn(0, bitmapHeight - 2)
        val r = (ceil(rect.right * bitmapWidth).toInt() + padOther).coerceIn(l + 1, bitmapWidth)
        val b = (ceil(rect.bottom * bitmapHeight).toInt() + padOther).coerceIn(t + 1, bitmapHeight)
        return android.graphics.Rect(l, t, r, b)
    }
}
