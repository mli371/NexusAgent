package com.nexusagent.context.domain;

public record ContextDebugMetadata(
        String reranker,
        int fusedCandidateCount,
        int rerankedCandidateCount,
        int selectedChildChunkCount,
        int expandedParentContextCount,
        int appliedBudgetChars,
        int usedBudgetChars,
        int skippedDuplicateParentCount,
        int skippedBudgetCount
) {
}
