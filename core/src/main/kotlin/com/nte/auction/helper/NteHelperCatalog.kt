package com.nte.auction.helper

import com.nte.auction.domain.GridSize
import com.nte.auction.domain.Quality

/**
 * Functionally equivalent catalog reconstructed from the public nte-auction-helper project.
 * The source repository currently has no LICENSE file, so this implementation keeps only
 * factual game data and independently-written Kotlin structures/logic.
 */
data class HelperCatalogItem(
    val name: String,
    val quality: Quality,
    val price: Long,
    val size: GridSize,
) {
    val width: Int get() = size.width
    val height: Int get() = size.height
    val cells: Int get() = size.cells
}

object NteHelperCatalog {
    val gold: List<HelperCatalogItem> = buildItems(
        quality = Quality.GOLD,
        prices = listOf(
            4975, 5041, 5047, 5555, 7500, 7547, 7610, 7648, 9040, 9938,
            11974, 11981, 12116, 12164, 13632, 15024, 15037, 15108, 15309,
            18031, 18193, 18288, 19926, 20238, 21012, 22297, 22336, 22770,
            22847, 27159, 27246, 30211, 30264, 37558, 38316, 51077, 60040,
            60285, 62456, 76591, 77777, 80754, 88888, 101403, 101537, 101554,
            111111, 124816, 202798, 271827,
        ),
        dimensions = listOf(
            1 to 1, 1 to 1, 1 to 1, 1 to 1, 1 to 2, 2 to 1, 2 to 1, 1 to 1, 1 to 1, 1 to 1,
            3 to 1, 2 to 2, 1 to 3, 3 to 1, 1 to 2, 1 to 3, 2 to 2, 2 to 2, 1 to 4, 2 to 2,
            3 to 1, 1 to 5, 2 to 2, 3 to 1, 1 to 3, 2 to 3, 2 to 3, 2 to 3, 2 to 3, 3 to 3,
            1 to 5, 2 to 3, 3 to 3, 2 to 1, 1 to 2, 5 to 5, 1 to 2, 2 to 1, 1 to 4, 2 to 3,
            1 to 3, 2 to 2, 2 to 3, 4 to 1, 4 to 3, 4 to 4, 4 to 1, 4 to 3, 4 to 5, 4 to 5,
        ),
        names = listOf(
            "落日珍珠", "光之方", "第23页", "海星", "『方盒科技』智能手表", "缄默花瓣", "大满贯手握", "桂冠",
            "猫丸秘制豚骨拉面", "绯红宝石", "自由数据线", "『方盒科技』充电宝", "『方盒科技』电容笔", "骑士车贴",
            "黄釉雅器", "气象指南", "盈雪点翠", "蓝焰火花塞", "生春鞘", "万能适配器", "名为尊贵的冠冕", "『截云』",
            "凝华海露", "缠枝花纹筷", "万花筒", "心猎铁骑L3-以一当千", "絮语之种", "心猎铁骑L2-闪焰冲锋",
            "心猎铁骑L1-必杀一击", "轮椅模型", "『墙身好伙伴！』", "海盐心迷宫", "浅绯祈手办", "巡哨-干练精英",
            "灿金环", "万有星仪", "算力面包", "澄空之眼", "青花花瓶", "映像机", "名为显赫的权杖", "双颈玉瓶-白",
            "片刻驻足", "琉璃尾", "条纹椰", "赤色来电", "金龙鱼", "罐装随心泥", "大饼", "乔望尼金雕像",
        ),
    )

    val redAll: List<HelperCatalogItem> = buildItems(
        quality = Quality.RED,
        prices = listOf(
            29977, 31618, 50000, 52000, 59440, 61740, 61803, 76008, 78800,
            80608, 81088, 88600, 100000, 101860, 150051, 200201, 239342,
            240208, 260423, 280000, 288888, 300000, 366112, 500001, 577777,
            1314520, 5121024, 11235813, 20171210, 22668888,
        ),
        dimensions = listOf(
            2 to 1, 2 to 2, 1 to 1, 4 to 4, 4 to 4, 1 to 1, 1 to 1, 2 to 1, 2 to 1, 2 to 3,
            2 to 4, 2 to 2, 3 to 3, 1 to 3, 1 to 2, 2 to 2, 3 to 2, 3 to 3, 3 to 3, 1 to 2,
            5 to 1, 1 to 4, 3 to 2, 5 to 5, 3 to 2, 1 to 1, 2 to 1, 2 to 2, 5 to 5, 5 to 5,
        ),
        names = listOf(
            "酥酥酥天丼", "『摇星』", "『泪滴』", "鲸歌", "K01模型", "混沌谜核", "『几何』摆件", "图特U盘",
            "鸣佩", "霜铁交响", "莹碧翡翠", "崭新限量排球", "吨吨锤", "细颈连纹瓶", "他山之石", "永生花环",
            "便携式折叠屏", "九格小食", "一簇幽火", "酷辣辣辣条", "曜目权柄", "白龙王", "未知门禁卡", "储钱小哼",
            "古旧手提箱", "『永恒之心』", "超级存储盘", "碧波天垂", "pendragon模型", "金锦鲤雕像",
        ),
    )

    /** Original estimator intentionally excludes red entries above one million. */
    val redEstimationPool: List<HelperCatalogItem> = redAll.filter { it.price <= 1_000_000L }

    /** Current red_weights.json shipped by the reference project, aligned with redEstimationPool. */
    val redPriorWeights: List<Double> = listOf(
        4.32, 4.64, 5.68, 4.64, 5.12, 4.24, 5.12, 4.88, 4.72, 4.32,
        4.62, 4.06, 4.27, 3.78, 3.90, 3.36, 2.65, 2.95, 2.85, 3.10,
        2.85, 3.05, 2.60, 2.24, 2.56,
    )

    val defaultCustomBids: List<Long> = listOf(588888, 888888, 388888, 1, 188888, 488888, 88888)

    fun candidates(quality: Quality, width: Int, height: Int): List<HelperCatalogItem> {
        val pool = when (quality) {
            Quality.GOLD -> gold
            Quality.RED -> redAll
            else -> emptyList()
        }
        return pool.filter { it.size.width == width && it.size.height == height }
    }

    fun goldByPrice(price: Long, doubled: Boolean = false): HelperCatalogItem? {
        val base = if (doubled && gold.none { it.price == price }) price / 2 else price
        return gold.firstOrNull { it.price == base }
    }

    private fun buildItems(
        quality: Quality,
        prices: List<Int>,
        dimensions: List<Pair<Int, Int>>,
        names: List<String>,
    ): List<HelperCatalogItem> {
        require(prices.size == dimensions.size && prices.size == names.size)
        return prices.indices.map { index ->
            HelperCatalogItem(
                name = names[index],
                quality = quality,
                price = prices[index].toLong(),
                size = GridSize(dimensions[index].first, dimensions[index].second),
            )
        }
    }
}
