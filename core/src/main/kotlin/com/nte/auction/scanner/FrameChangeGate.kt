package com.nte.auction.scanner

/**
 * 与真实 Bitmap 无关的变化门控接口。
 * Android 层只需提供当前帧的轻量签名，就能决定是否值得跑完整 OCR/CV。
 */
class FrameChangeGate(
    private val threshold: Double = 0.08,
) {
    private var previous: LongArray? = null

    fun shouldAnalyze(signature: LongArray): Boolean {
        val before = previous
        previous = signature.copyOf()
        if (before == null || before.size != signature.size) return true
        if (signature.isEmpty()) return false
        var changed = 0
        for (i in signature.indices) {
            if (before[i] != signature[i]) changed++
        }
        return changed.toDouble() / signature.size.toDouble() >= threshold
    }

    fun reset() {
        previous = null
    }
}
