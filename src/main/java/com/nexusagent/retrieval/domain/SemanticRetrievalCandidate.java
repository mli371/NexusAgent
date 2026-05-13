package com.nexusagent.retrieval.domain;

import java.util.UUID;

public record SemanticRetrievalCandidate(
        UUID childChunkId,
        UUID documentId,
        UUID parentChunkId,
        int chunkIndex,
        String previewText,
        int vectorRank,
        double vectorDistance
) {
}
