package com.nte.auction.helper

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class NteHelperAnalyzerTest {
    @Test
    fun catalogMatchesReferenceDataset() {
        assertEquals(50, NteHelperCatalog.gold.size)
        assertEquals(30, NteHelperCatalog.redAll.size)
        assertEquals(25, NteHelperCatalog.redEstimationPool.size)
        assertEquals(50, NteHelperCatalog.gold.map { it.price }.distinct().size)
    }

    @Test
    fun redPriorIsNormalizedAndConditionable() {
        val model = HelperRedProbabilityModel.build(HelperMemorySnapshot())
        assertTrue(kotlin.math.abs(model.probabilities.values.sum() - 1.0) < 1e-9)
        val price = NteHelperCatalog.redEstimationPool.first().price
        val before = model.weightedCounts.getValue(price)
        val conditioned = HelperRedProbabilityModel.conditionOnKnown(model, listOf(price))
        assertTrue(conditioned.weightedCounts.getValue(price) <= before)
        assertTrue(kotlin.math.abs(conditioned.probabilities.values.sum() - 1.0) < 1e-9)
    }

    @Test
    fun knownTwoGoldItemsProduceExactCompatibleRow() {
        val first = NteHelperCatalog.gold[0]
        val second = NteHelperCatalog.gold[1]
        val average = (first.price + second.price) / 2.0
        val result = NteHelperAnalyzer().analyze(
            HelperAnalyzeRequest(
                goldAverage = average,
                totalItems = 2,
                purpleCount = 0,
                knownGoldCount = 2,
                knownGoldPrices = listOf(first.price, second.price),
                goldTotalCells = first.cells + second.cells,
            )
        )
        val row = result.rows.singleOrNull()
        assertNotNull(row)
        assertEquals(2, row.goldCount)
        assertEquals(0, row.redCount)
        assertTrue(row.combos.any { combo ->
            combo.prices.sorted() == listOf(first.price, second.price).sorted()
        })
    }

    @Test
    fun estimateWithoutTotalItemsStillIncludesGoldValue() {
        val item = NteHelperCatalog.gold.first()
        val result = NteHelperAnalyzer().analyze(
            HelperAnalyzeRequest(
                goldAverage = item.price.toDouble(),
                knownGoldCount = 1,
                knownGoldPrices = listOf(item.price),
                knownRedCount = 0,
            )
        )
        val row = result.rows.firstOrNull()
        assertNotNull(row)
        assertTrue(row.totalValue >= item.price)
    }

    @Test
    fun redRegionUsesStrictOrientationCandidates() {
        val candidates = NteHelperCatalog.candidates(
            com.nte.auction.domain.Quality.RED,
            width = 2,
            height = 1,
        )
        assertTrue(candidates.isNotEmpty())
        assertTrue(candidates.all { it.size.width == 2 && it.size.height == 1 })
        assertTrue(NteHelperCatalog.candidates(com.nte.auction.domain.Quality.RED, 6, 6).isEmpty())
    }
}
