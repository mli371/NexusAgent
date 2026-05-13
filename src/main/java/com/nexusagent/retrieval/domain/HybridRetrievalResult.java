package com.nexusagent.retrieval.domain;

import java.util.List;

public record HybridRetrievalResult(
        String query,
        List<SemanticRetrievalCandidate> vectorCandidates,
        List<FullTextRetrievalCandidate> fullTextCandidates,
        List<FusedRetrievalCandidate> fusedCandidates
) {

    public HybridRetrievalResult {
        vectorCandidates = List.copyOf(vectorCandidates);
        fullTextCandidates = List.copyOf(fullTextCandidates);
        fusedCandidates = List.copyOf(fusedCandidates);
    }
}
