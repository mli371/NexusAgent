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
        int skippedBudgetCount,
        String allocationStrategy,
        int representativeChildCount,
        int reservedChildChars,
        int trimmedParentCount,
        java.util.List<ContextAllocation> allocations
) {
    public ContextDebugMetadata {
        allocations = allocations == null ? java.util.List.of() : java.util.List.copyOf(allocations);
    }

    public ContextDebugMetadata(String reranker, int fusedCandidateCount, int rerankedCandidateCount,
                                int selectedChildChunkCount, int expandedParentContextCount, int appliedBudgetChars,
                                int usedBudgetChars, int skippedDuplicateParentCount, int skippedBudgetCount) {
        this(reranker, fusedCandidateCount, rerankedCandidateCount, selectedChildChunkCount, expandedParentContextCount,
                appliedBudgetChars, usedBudgetChars, skippedDuplicateParentCount, skippedBudgetCount,
                "not_available", 0, 0, 0, java.util.List.of());
    }
}
