package com.nte.auction

import com.nte.auction.domain.*
import com.nte.auction.estimator.*
import com.nte.auction.scanner.ScanCoverageTracker
import com.nte.auction.solver.*

private fun assertThat(condition: Boolean, message: String) {
    if (!condition) error("TEST FAILED: $message")
}

private fun sampleCollectibles(): List<Collectible> = listOf(
    Collectible("g1", "金A", CollectibleType.JEWEL, Quality.GOLD, GridSize(1, 1), 100, "s1"),
    Collectible("g2", "金B", CollectibleType.JEWEL, Quality.GOLD, GridSize(1, 1), 300, "s2"),
    Collectible("p1", "紫A", CollectibleType.JEWEL, Quality.PURPLE, GridSize(1, 1), 50, "s3"),
    Collectible("r1", "红A", CollectibleType.TECHNOLOGY, Quality.RED, GridSize(2, 2), 500, "s4"),
    Collectible("p2", "紫B", CollectibleType.TECHNOLOGY, Quality.PURPLE, GridSize(2, 2), 80, "s5"),
    Collectible("g3", "金C", CollectibleType.TECHNOLOGY, Quality.GOLD, GridSize(2, 2), 200, "s6"),
)

private fun testBoundedCombinationSearch() {
    val result = BoundedCombinationSearch().search(
        values = listOf(100, 300, 500),
        count = 2,
        low = 400,
        high = 400,
        maxRepeat = 2,
    )
    assertThat(result.any { it.sum() == 400L }, "bounded combination should find 100+300")
    assertThat(result.all { it.size == 2 && it.sum() == 400L }, "all combinations should respect count and sum")
}

private fun testEvidenceResolutionAndGlobalPropagation() {
    val session = AuctionSession("test")
    val item1 = WarehouseItem(1, 0, 0, GridSize(1, 1))
    item1.evidenceHistory += QualityEvidence(1, Quality.GOLD, 1, 0.99, EvidenceSource.USER)
    val item2 = WarehouseItem(2, 0, 1, GridSize(1, 1))
    val item3 = WarehouseItem(3, 1, 0, GridSize(2, 2))
    item3.evidenceHistory += TypeEvidence(3, CollectibleType.TECHNOLOGY, 1, 0.99, EvidenceSource.USER)
    session.warehouse.items[1] = item1
    session.warehouse.items[2] = item2
    session.warehouse.items[3] = item3

    session.globalEvidence += QualityCountEvidence(Quality.GOLD, 2, 2, 0.99, EvidenceSource.OCR)
    session.globalEvidence += QualityCountEvidence(Quality.RED, 1, 2, 0.99, EvidenceSource.OCR)
    session.globalEvidence += QualityAveragePriceEvidence(Quality.GOLD, 200, 0, 2, 0.99, EvidenceSource.OCR)

    val resolution = CandidateResolver(sampleCollectibles()).resolve(session)
    assertThat(resolution.isValid, "resolution should be valid: ${resolution.contradictions}")
    assertThat(resolution.byItem.getValue(3).all { it.quality == Quality.RED }, "red count should force item3 to red")
    assertThat(resolution.byItem.getValue(2).all { it.quality == Quality.GOLD }, "gold count should force item2 to gold")

    val analysis = WarehouseEstimator(
        CandidateResolver(sampleCollectibles()),
        MonteCarloEstimator(targetSamples = 2_000, seed = 7),
    ).analyze(session)
    assertThat(analysis.contradictions.isEmpty(), "analysis should not contradict")
    assertThat(analysis.distribution.p50 == 900L, "gold avg + red count should make total 900, got ${analysis.distribution.p50}")
    assertThat(analysis.distribution.min == 900L && analysis.distribution.max == 900L, "distribution should collapse to exact value")
}

private fun testSystemEstimateLowerBound() {
    val session = AuctionSession("min-bound")
    session.warehouse.items[1] = WarehouseItem(1, 0, 0, GridSize(1, 1))
    session.globalEvidence += SystemEstimateEvidence(250, 1, 1.0, EvidenceSource.OCR)
    val resolution = CandidateResolver(sampleCollectibles()).resolve(session)
    val distribution = MonteCarloEstimator(targetSamples = 500, seed = 3).estimate(resolution, session.globalEvidence)
    assertThat(distribution.min >= 250, "system estimate must act as lower bound")
}

private fun testScanCoverage() {
    val scan = WarehouseScanSession("scan", ScanMode.FAST_REFRESH)
    val tracker = ScanCoverageTracker()
    tracker.setTotalRows(scan, 10)
    tracker.observeRows(scan, 0, 4)
    assertThat(scan.coverage == 0.5, "coverage after 5/10 rows should be 0.5")
    tracker.observeRows(scan, 4, 9)
    assertThat(scan.state == ScanState.COMPLETE, "overlap scan should complete once all rows covered")
}

private fun testBidAdvisor() {
    val dist = EstimateDistribution(
        acceptedSamples = 100,
        attempts = 100,
        p10 = 800,
        p25 = 900,
        p50 = 1000,
        mean = 1000,
        p75 = 1200,
        p90 = 1400,
        min = 700,
        max = 1600,
        knownValue = 600,
        unknownExpectedValue = 400,
        unknownRatio = 0.4,
        confidence = 0.7,
    )
    val advice = BidAdvisor().advise(
        distribution = dist,
        round = 3,
        secondHighestBid = 600,
        currentRequiredBid = 610,
        riskProfile = RiskProfile.BALANCED,
        safetyMargin = 0.0,
    )
    assertThat(advice.forcedClosePrice == 781L, "round3 close price should be ceil(600*1.3)+1")
    assertThat(advice.action == BidAdvice.Action.BID, "781 should be below conservative p25=900")
}

private fun testViewportMatchingAndMerge() {
    val first = com.nte.auction.scanner.WarehouseViewportObservation(
        frameId = 1,
        visibleRows = 6,
        items = listOf(
            com.nte.auction.scanner.ViewportItemObservation(0, 0, GridSize(2, 2), "A"),
            com.nte.auction.scanner.ViewportItemObservation(2, 2, GridSize(1, 2), "B"),
            com.nte.auction.scanner.ViewportItemObservation(4, 0, GridSize(2, 1), "C"),
        )
    )
    val second = com.nte.auction.scanner.WarehouseViewportObservation(
        frameId = 2,
        visibleRows = 6,
        items = listOf(
            com.nte.auction.scanner.ViewportItemObservation(0, 2, GridSize(1, 2), "B"),
            com.nte.auction.scanner.ViewportItemObservation(2, 0, GridSize(2, 1), "C"),
            com.nte.auction.scanner.ViewportItemObservation(4, 1, GridSize(2, 2), "D"),
        )
    )
    val matcher = com.nte.auction.scanner.ViewportMatcher(maxSearchRows = 8)
    val match = matcher.match(0, first, second) ?: error("expected viewport match")
    assertThat(match.globalStartRow == 2, "second viewport should start at global row 2, got ${match.globalStartRow}")
    assertThat(match.overlapMatches == 2, "B/C should provide two overlaps")

    val warehouse = Warehouse()
    val merger = com.nte.auction.scanner.WarehouseObservationMerger()
    val r1 = merger.merge(warehouse, 0, first)
    val r2 = merger.merge(warehouse, match.globalStartRow, second)
    assertThat(r1.createdItems == 3, "first viewport should create 3 items")
    assertThat(r2.createdItems == 1, "second viewport should only create D; overlap must dedupe")
    assertThat(warehouse.items.size == 4, "merged warehouse should contain 4 logical items")
}

private fun testFrameChangeGate() {
    val gate = com.nte.auction.scanner.FrameChangeGate(threshold = 0.25)
    assertThat(gate.shouldAnalyze(longArrayOf(1,2,3,4)), "first frame must analyze")
    assertThat(!gate.shouldAnalyze(longArrayOf(1,2,3,4)), "identical frame should skip")
    assertThat(gate.shouldAnalyze(longArrayOf(1,9,8,4)), "50% changed frame should analyze")
}

fun main() {
    val tests = listOf(
        "BoundedCombinationSearch" to ::testBoundedCombinationSearch,
        "EvidenceAndPropagation" to ::testEvidenceResolutionAndGlobalPropagation,
        "SystemEstimateLowerBound" to ::testSystemEstimateLowerBound,
        "ScanCoverage" to ::testScanCoverage,
        "BidAdvisor" to ::testBidAdvisor,
        "ViewportMatchingAndMerge" to ::testViewportMatchingAndMerge,
        "FrameChangeGate" to ::testFrameChangeGate,
    )
    tests.forEach { (name, test) ->
        test()
        println("PASS $name")
    }
    println("ALL CORE TESTS PASSED (${tests.size})")
}
