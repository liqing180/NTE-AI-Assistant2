package com.nte.auction.estimator

import kotlin.math.ceil

enum class RiskProfile { CONSERVATIVE, BALANCED, AGGRESSIVE }

data class BidAdvice(
    val forcedClosePrice: Long?,
    val conservativeLimit: Long,
    val balancedLimit: Long,
    val aggressiveLimit: Long,
    val selectedLimit: Long,
    val action: Action,
) {
    enum class Action { BID, CAN_CHASE, PASS }
}

class BidAdvisor {
    /**
     * 回合强制成交倍率：沿用公开规则参数，保持为配置而不是散落在 UI 中。
     * round 1..4 有阈值，round 5+ 最高价逻辑不返回 forcedClosePrice。
     */
    private val closeMultipliers = mapOf(
        1 to 2.0,
        2 to 1.6,
        3 to 1.3,
        4 to 1.1,
    )

    fun advise(
        distribution: EstimateDistribution,
        round: Int,
        secondHighestBid: Long?,
        currentRequiredBid: Long,
        riskProfile: RiskProfile = RiskProfile.BALANCED,
        safetyMargin: Double = 0.03,
    ): BidAdvice {
        val conservative = (distribution.p25 * (1.0 - safetyMargin)).toLong().coerceAtLeast(0)
        val balanced = (distribution.p50 * (1.0 - safetyMargin)).toLong().coerceAtLeast(0)
        val aggressive = (distribution.p75 * (1.0 - safetyMargin)).toLong().coerceAtLeast(0)
        val selected = when (riskProfile) {
            RiskProfile.CONSERVATIVE -> conservative
            RiskProfile.BALANCED -> balanced
            RiskProfile.AGGRESSIVE -> aggressive
        }
        val forcedClose = secondHighestBid?.let { second ->
            closeMultipliers[round]?.let { multiplier -> ceil(second * multiplier).toLong() + 1L }
        }
        val actionableBid = forcedClose ?: currentRequiredBid
        val action = when {
            actionableBid <= conservative -> BidAdvice.Action.BID
            actionableBid <= selected -> BidAdvice.Action.CAN_CHASE
            else -> BidAdvice.Action.PASS
        }
        return BidAdvice(
            forcedClosePrice = forcedClose,
            conservativeLimit = conservative,
            balancedLimit = balanced,
            aggressiveLimit = aggressive,
            selectedLimit = selected,
            action = action,
        )
    }
}
