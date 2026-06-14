package com.example.hellorokid.shared.camera

import android.graphics.RectF
import com.example.hellorokid.shared.image.NormalizedRect

/**
 * 取景框几何：绿框绘制与成片裁切共用同一套计算，避免 HUD 与照片错位。
 */
object FramingGeometry {

    /** 名片宽:高（横向名片） */
    const val CARD_ASPECT_RATIO = 1.58f
    /** 框中心水平位置（0=左，1=右） */
    const val HORIZONTAL_BIAS = 0.44f
    /** 框中心垂直位置 */
    const val VERTICAL_BIAS = 0.46f
    /** 相对短边的框宽度占比（偏小：引导持远一点，利于对焦） */
    const val FRAME_SIZE_RATIO = 0.62f

    /**
     * 成片裁切相对绿框的外扩（成片 > 绿框，防止边缘文字被切）。
     * 左侧多留：Rokid 270° 下左侧日文/中文名最易缺字。
     */
    const val CROP_MARGIN_LEFT = 0.10f
    const val CROP_MARGIN_RIGHT = 0.06f
    const val CROP_MARGIN_VERTICAL = 0.06f

    fun computeFrameRect(viewWidth: Int, viewHeight: Int, previewPortrait: Boolean): RectF {
        if (viewWidth <= 0 || viewHeight <= 0) {
            return RectF(0.08f, 0.22f, 0.92f, 0.78f)
        }
        val vw = viewWidth.toFloat()
        val vh = viewHeight.toFloat()

        val base = if (previewPortrait || vh >= vw) vw else vh
        var frameW = base * FRAME_SIZE_RATIO
        var frameH = frameW / CARD_ASPECT_RATIO

        val maxH = vh * 0.55f
        val maxW = vw * 0.82f
        if (frameH > maxH) {
            frameH = maxH
            frameW = frameH * CARD_ASPECT_RATIO
        }
        if (frameW > maxW) {
            frameW = maxW
            frameH = frameW / CARD_ASPECT_RATIO
        }

        val cx = vw * HORIZONTAL_BIAS
        val cy = vh * VERTICAL_BIAS
        return RectF(
            cx - frameW / 2f,
            cy - frameH / 2f,
            cx + frameW / 2f,
            cy + frameH / 2f
        )
    }

    fun computeNormalizedFrameRect(viewWidth: Int, viewHeight: Int, previewPortrait: Boolean): NormalizedRect {
        val frame = computeFrameRect(viewWidth, viewHeight, previewPortrait)
        if (viewWidth <= 0 || viewHeight <= 0) {
            return NormalizedRect(0.08f, 0.22f, 0.92f, 0.78f)
        }
        return NormalizedRect(
            left = frame.left / viewWidth,
            top = frame.top / viewHeight,
            right = frame.right / viewWidth,
            bottom = frame.bottom / viewHeight
        )
    }
}
