package com.nte.auction.solver

/**
 * 独立实现的有界组合搜索：
 * - 固定选择件数 count
 * - 每个价格最多重复 maxRepeat 次
 * - 总和必须落在 [low, high]
 * - 通过 suffix min/max 界提前剪枝
 *
 * 用于金色均价/总价反推，也可复用于其他离散值组合约束。
 */
class BoundedCombinationSearch {
    fun search(
        values: List<Long>,
        count: Int,
        low: Long,
        high: Long,
        maxRepeat: Int = 2,
        maxResults: Int = 500,
    ): List<List<Long>> {
        require(count >= 0)
        require(maxRepeat >= 1)
        if (count == 0) return if (0L in low..high) listOf(emptyList()) else emptyList()
        if (values.isEmpty() || low > high) return emptyList()

        val sorted = values.distinct().sorted()
        val m = sorted.size
        val minTable = Array(m + 1) { LongArray(count + 1) { INF } }
        val maxTable = Array(m + 1) { LongArray(count + 1) { NEG_INF } }
        minTable[m][0] = 0L
        maxTable[m][0] = 0L

        for (i in m - 1 downTo 0) {
            minTable[i][0] = 0L
            maxTable[i][0] = 0L
            for (need in 1..count) {
                var minValue = INF
                var maxValue = NEG_INF
                val takeMax = minOf(maxRepeat, need)
                for (take in 0..takeMax) {
                    val remaining = need - take
                    if (minTable[i + 1][remaining] != INF) {
                        minValue = minOf(minValue, take * sorted[i] + minTable[i + 1][remaining])
                    }
                    if (maxTable[i + 1][remaining] != NEG_INF) {
                        maxValue = maxOf(maxValue, take * sorted[i] + maxTable[i + 1][remaining])
                    }
                }
                minTable[i][need] = minValue
                maxTable[i][need] = maxValue
            }
        }

        val out = mutableListOf<List<Long>>()
        val working = mutableListOf<Long>()

        fun dfs(index: Int, remaining: Int, sum: Long) {
            if (out.size >= maxResults) return
            if (remaining == 0) {
                if (sum in low..high) out += working.toList()
                return
            }
            if (index >= m) return
            if (minTable[index][remaining] == INF) return
            if (sum + minTable[index][remaining] > high) return
            if (sum + maxTable[index][remaining] < low) return

            val value = sorted[index]
            val takeMax = minOf(maxRepeat, remaining)
            for (take in 0..takeMax) {
                val nextRemaining = remaining - take
                val nextSum = sum + take * value
                if (nextRemaining > 0) {
                    if (minTable[index + 1][nextRemaining] == INF) continue
                    if (nextSum + minTable[index + 1][nextRemaining] > high) continue
                    if (nextSum + maxTable[index + 1][nextRemaining] < low) continue
                }
                repeat(take) { working += value }
                dfs(index + 1, nextRemaining, nextSum)
                repeat(take) { working.removeAt(working.lastIndex) }
                if (out.size >= maxResults) return
            }
        }

        dfs(0, count, 0L)
        return out
    }

    private companion object {
        const val INF: Long = Long.MAX_VALUE / 4
        const val NEG_INF: Long = Long.MIN_VALUE / 4
    }
}
