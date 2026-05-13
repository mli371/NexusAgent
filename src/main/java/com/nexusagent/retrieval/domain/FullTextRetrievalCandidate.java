package com.nexusagent.retrieval.domain;

import java.util.UUID;

public record FullTextRetrievalCandidate(
        UUID childChunkId,
        UUID documentId,
        UUID parentChunkId,
        int chunkIndex,
        String previewText,
        int fullTextRank,
        double fullTextScore
) {
}
