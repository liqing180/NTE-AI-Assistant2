package com.nte.auction.scanner

import com.nte.auction.domain.GridSize

data class ViewportItemObservation(
    val localRow: Int,
    val localColumn: Int,
    val size: GridSize,
    /** 由后续视觉层生成：轮廓/边缘/颜色的稳定低维签名。 */
    val fingerprint: String,
    val confidence: Double = 1.0,
)

data class WarehouseViewportObservation(
    val frameId: Long,
    val visibleRows: Int,
    val items: List<ViewportItemObservation>,
)

data class ViewportMatch(
    val globalStartRow: Int,
    val confidence: Double,
    val overlapMatches: Int,
    val comparedItems: Int,
)

/**
 * 通过相邻 viewport 的重叠藏品估计当前 viewport 在完整仓库中的起始行。
 * 不依赖滚动条像素位置；滚动条只会在 Android 层作为额外先验。
 */
class ViewportMatcher(
    private val maxSearchRows: Int = 24,
) {
    fun match(
        previousGlobalStartRow: Int,
        previous: WarehouseViewportObservation,
        current: WarehouseViewportObservation,
    ): ViewportMatch? {
        if (previous.items.isEmpty() || current.items.isEmpty()) return null

        val searchStart = maxOf(0, previousGlobalStartRow - maxSearchRows)
        val searchEnd = previousGlobalStartRow + maxSearchRows
        var best: ViewportMatch? = null

        for (candidateStart in searchStart..searchEnd) {
            var matches = 0
            var weightedMatches = 0.0
            var eligible = 0
            for (prev in previous.items) {
                val prevGlobalRow = previousGlobalStartRow + prev.localRow
                for (cur in current.items) {
                    if (prev.fingerprint != cur.fingerprint) continue
                    if (prev.localColumn != cur.localColumn) continue
                    if (prev.size != cur.size) continue
                    eligible++
                    val curGlobalRow = candidateStart + cur.localRow
                    if (prevGlobalRow == curGlobalRow) {
                        matches++
                        weightedMatches += minOf(prev.confidence, cur.confidence)
                    }
                }
            }
            if (matches == 0) continue
            val denominator = minOf(previous.items.size, current.items.size).coerceAtLeast(1)
            val raw = weightedMatches / denominator.toDouble()
            // 至少两个重叠对象时明显提高可信度；单对象仍可作为弱匹配。
            val confidence = (raw * if (matches >= 2) 1.15 else 0.70).coerceIn(0.0, 1.0)
            val result = ViewportMatch(candidateStart, confidence, matches, eligible)
            if (best == null || result.confidence > best.confidence ||
                (result.confidence == best.confidence && result.overlapMatches > best.overlapMatches)
            ) {
                best = result
            }
        }
        return best
    }
}
