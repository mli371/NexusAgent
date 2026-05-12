package com.nexusagent.embeddings.domain;

import java.util.UUID;

public record EmbeddingStatus(
        UUID documentId,
        int childChunkCount,
        int embeddedChildChunkCount,
        int missingChildChunkCount,
        boolean complete,
        String provider,
        String modelName,
        int dimension
) {
}
