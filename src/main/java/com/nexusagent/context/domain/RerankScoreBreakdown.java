package com.nexusagent.context.domain;

public record RerankScoreBreakdown(
        double rrfComponent,
        double keywordOverlapScore,
        double sourceBoost,
        double diversityPenalty
) {
}
