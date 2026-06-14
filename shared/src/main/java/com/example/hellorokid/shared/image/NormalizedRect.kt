package com.example.hellorokid.shared.image

/**
 * 归一化矩形 [0, 1]，用于取景框与裁切区域映射。
 */
data class NormalizedRect(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float
) {
    init {
        require(left < right && top < bottom) {
            "Invalid rect: ($left,$top)-($right,$bottom)"
        }
    }

    fun withMargin(margin: Float): NormalizedRect {
        return NormalizedRect(
            left = (left - margin).coerceAtLeast(0f),
            top = (top - margin).coerceAtLeast(0f),
            right = (right + margin).coerceAtMost(1f),
            bottom = (bottom + margin).coerceAtMost(1f)
        )
    }

    fun clamp(): NormalizedRect {
        val l = left.coerceIn(0f, 1f)
        val t = top.coerceIn(0f, 1f)
        val r = right.coerceIn(l + 0.01f, 1f)
        val b = bottom.coerceIn(t + 0.01f, 1f)
        return NormalizedRect(l, t, r, b)
    }
}
