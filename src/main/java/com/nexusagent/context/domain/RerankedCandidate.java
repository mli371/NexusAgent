package com.nexusagent.context.domain;

import com.nexusagent.retrieval.domain.FusedRetrievalCandidate;

public record RerankedCandidate(
        FusedRetrievalCandidate candidate,
        int originalRank,
        int rerankedRank,
        double rerankScore,
        RerankScoreBreakdown scoreBreakdown,
        String reason
) {
}
