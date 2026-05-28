package com.nexusagent.context.domain;

import java.util.List;

import com.nexusagent.retrieval.domain.HybridRetrievalResult;

public record ContextBuildResult(
        String query,
        List<RerankedCandidate> rerankedCandidates,
        List<SelectedChildChunk> selectedChildChunks,
        List<ExpandedParentContext> expandedParentContexts,
        List<Citation> citations,
        String finalContextText,
        ContextDebugMetadata debugMetadata,
        HybridRetrievalResult retrievalResult
) {

    public ContextBuildResult {
        rerankedCandidates = List.copyOf(rerankedCandidates);
        selectedChildChunks = List.copyOf(selectedChildChunks);
        expandedParentContexts = List.copyOf(expandedParentContexts);
        citations = List.copyOf(citations);
    }

    public ContextBuildResult(
            String query,
            List<RerankedCandidate> rerankedCandidates,
            List<SelectedChildChunk> selectedChildChunks,
            List<ExpandedParentContext> expandedParentContexts,
            List<Citation> citations,
            String finalContextText,
            ContextDebugMetadata debugMetadata
    ) {
        this(
                query,
                rerankedCandidates,
                selectedChildChunks,
                expandedParentContexts,
                citations,
                finalContextText,
                debugMetadata,
                null
        );
    }
}
