package com.example.hellorokid.glass.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import androidx.core.content.ContextCompat
import com.example.hellorokid.glass.R
import com.example.hellorokid.shared.camera.FramingGeometry
import com.example.hellorokid.shared.image.NormalizedRect

/**
 * 名片取景框：几何计算与成片裁切共用 [FramingGeometry]。
 */
class CardFramingOverlay @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val framePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 5f
        color = ContextCompat.getColor(context, R.color.rokid_green)
    }

    private val dimPaint = Paint().apply {
        color = 0x99000000.toInt()
        style = Paint.Style.FILL
    }

    private val guidePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.rokid_green)
        alpha = 120
        strokeWidth = 2f
        style = Paint.Style.STROKE
    }

    private val frameRect = RectF()
    private var afFocused = false
    private var previewPortrait = true

    fun setPreviewPortrait(portrait: Boolean) {
        if (previewPortrait != portrait) {
            previewPortrait = portrait
            invalidate()
        }
    }

    fun setAfFocused(focused: Boolean) {
        if (afFocused != focused) {
            afFocused = focused
            framePaint.strokeWidth = if (focused) 7f else 5f
            framePaint.alpha = if (focused) 255 else 200
            invalidate()
        }
    }

    fun getFramingRect(): RectF {
        if (width == 0 || height == 0) {
            return FramingGeometry.computeFrameRect(1, 1, previewPortrait)
        }
        computeFrameRect()
        return RectF(frameRect)
    }

    /** 与裁切映射使用同一套归一化绿框 */
    fun getNormalizedFramingRect(): NormalizedRect {
        return FramingGeometry.computeNormalizedFrameRect(width, height, previewPortrait)
    }

    private fun computeFrameRect() {
        frameRect.set(FramingGeometry.computeFrameRect(width, height, previewPortrait))
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (width == 0 || height == 0) return
        computeFrameRect()

        canvas.drawRect(0f, 0f, width.toFloat(), frameRect.top, dimPaint)
        canvas.drawRect(0f, frameRect.bottom, width.toFloat(), height.toFloat(), dimPaint)
        canvas.drawRect(0f, frameRect.top, frameRect.left, frameRect.bottom, dimPaint)
        canvas.drawRect(frameRect.right, frameRect.top, width.toFloat(), frameRect.bottom, dimPaint)

        canvas.drawRect(frameRect, framePaint)

        val corner = 22f
        val thick = framePaint.strokeWidth
        drawCorner(canvas, frameRect.left, frameRect.top, corner, thick, true, true)
        drawCorner(canvas, frameRect.right, frameRect.top, corner, thick, false, true)
        drawCorner(canvas, frameRect.left, frameRect.bottom, corner, thick, true, false)
        drawCorner(canvas, frameRect.right, frameRect.bottom, corner, thick, false, false)

        val markX = frameRect.left - 18f
        if (markX > 8f) {
            canvas.drawLine(markX, frameRect.top + 12f, markX, frameRect.bottom - 12f, guidePaint)
        }
    }

    private fun drawCorner(
        canvas: Canvas,
        x: Float,
        y: Float,
        len: Float,
        thick: Float,
        left: Boolean,
        top: Boolean
    ) {
        val paint = Paint(framePaint).apply {
            strokeWidth = thick + 2f
            style = Paint.Style.STROKE
        }
        val hx = if (left) x + len else x - len
        val hy = if (top) y + len else y - len
        canvas.drawLine(x, y, hx, y, paint)
        canvas.drawLine(x, y, x, hy, paint)
    }
}
