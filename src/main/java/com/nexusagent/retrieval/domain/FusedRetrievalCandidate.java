package com.nexusagent.retrieval.domain;

import java.util.UUID;

public record FusedRetrievalCandidate(
        UUID childChunkId,
        UUID documentId,
        UUID parentChunkId,
        int chunkIndex,
        String previewText,
        RetrievalSource source,
        Integer vectorRank,
        Double vectorDistance,
        Integer fullTextRank,
        Double fullTextScore,
        double rrfScore
) {
}
