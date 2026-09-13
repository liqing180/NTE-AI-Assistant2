package com.nte.auction.helper

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

object HelperBidController {
    private val _status = MutableStateFlow("自动出价需要启用无障碍服务")
    val status: StateFlow<String> = _status.asStateFlow()

    fun multiply(multiplier: Double) {
        val current = HelperFeatureStore.state.value.bidAmount ?: return
        HelperFeatureStore.setBidAmount((current * multiplier).toLong().coerceAtLeast(1L))
    }

    fun increment(delta: Long) {
        val current = HelperFeatureStore.state.value.bidAmount ?: 0L
        HelperFeatureStore.setBidAmount((current + delta).coerceAtLeast(1L))
    }

    fun useLowestEstimate() {
        HelperFeatureStore.state.value.analysis?.lowestEstimate?.let(HelperFeatureStore::setBidAmount)
    }

    fun performBid() {
        val amount = HelperFeatureStore.state.value.bidAmount
        if (amount == null || amount <= 0L) {
            _status.value = "请输入有效出价金额"
            return
        }
        _status.value = "正在自动出价…"
        HelperAutoBidAccessibilityService.bid(amount) { result ->
            _status.value = result.fold(
                onSuccess = { "自动出价完成：$amount" },
                onFailure = { "自动出价失败：${it.message ?: it::class.java.simpleName}" },
            )
        }
    }
}
