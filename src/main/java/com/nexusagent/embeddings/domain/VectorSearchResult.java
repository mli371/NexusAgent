package com.nexusagent.embeddings.domain;

import java.util.UUID;

public record VectorSearchResult(
        UUID childChunkId,
        UUID documentId,
        UUID parentChunkId,
        int chunkIndex,
        String text,
        double distance
) {
}
