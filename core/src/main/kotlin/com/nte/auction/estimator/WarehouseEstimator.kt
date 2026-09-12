package com.nte.auction.estimator

import com.nte.auction.domain.AuctionSession
import com.nte.auction.solver.CandidateResolver

data class WarehouseAnalysis(
    val distribution: EstimateDistribution,
    val contradictions: List<String>,
)

class WarehouseEstimator(
    private val candidateResolver: CandidateResolver,
    private val monteCarloEstimator: MonteCarloEstimator = MonteCarloEstimator(),
) {
    fun analyze(session: AuctionSession): WarehouseAnalysis {
        val resolution = candidateResolver.resolve(session)
        if (!resolution.isValid) {
            return WarehouseAnalysis(
                distribution = EstimateDistribution(
                    acceptedSamples = 0,
                    attempts = 0,
                    p10 = 0,
                    p25 = 0,
                    p50 = 0,
                    mean = 0,
                    p75 = 0,
                    p90 = 0,
                    min = 0,
                    max = 0,
                    knownValue = 0,
                    unknownExpectedValue = 0,
                    unknownRatio = 1.0,
                    confidence = 0.0,
                ),
                contradictions = resolution.contradictions,
            )
        }
        return WarehouseAnalysis(
            distribution = monteCarloEstimator.estimate(resolution, session.globalEvidence),
            contradictions = emptyList(),
        )
    }
}
