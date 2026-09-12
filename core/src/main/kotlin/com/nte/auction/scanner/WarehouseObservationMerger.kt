package com.nte.auction.scanner

import com.nte.auction.domain.Warehouse
import com.nte.auction.domain.WarehouseItem

/**
 * 把 viewport 局部 observation 合并为完整仓库的稳定 Item。
 * 第一阶段按“全局左上格 + 尺寸 + fingerprint”去重；后续视觉层会加入轮廓 embedding 距离。
 */
class WarehouseObservationMerger(
    private var nextItemId: Long = 1L,
) {
    private val keyToItemId = linkedMapOf<ItemKey, Long>()

    fun merge(
        warehouse: Warehouse,
        viewportStartRow: Int,
        observation: WarehouseViewportObservation,
    ): MergeResult {
        val seen = mutableListOf<Long>()
        var created = 0
        for (item in observation.items) {
            val globalRow = viewportStartRow + item.localRow
            val key = ItemKey(globalRow, item.localColumn, item.size.width, item.size.height, item.fingerprint)
            val existingId = keyToItemId[key]
            val id = existingId ?: nextItemId++.also {
                keyToItemId[key] = it
                warehouse.items[it] = WarehouseItem(
                    id = it,
                    row = globalRow,
                    column = item.localColumn,
                    size = item.size,
                )
                created++
            }
            seen += id
            warehouse.maxObservedRow = maxOf(
                warehouse.maxObservedRow,
                globalRow + item.size.height - 1,
            )
        }
        return MergeResult(seen.distinct(), created)
    }

    data class MergeResult(
        val itemIds: List<Long>,
        val createdItems: Int,
    )

    private data class ItemKey(
        val row: Int,
        val column: Int,
        val width: Int,
        val height: Int,
        val fingerprint: String,
    )
}
