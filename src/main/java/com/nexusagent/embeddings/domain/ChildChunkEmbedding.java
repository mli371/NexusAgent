package com.nexusagent.embeddings.domain;

import java.time.OffsetDateTime;
import java.util.UUID;

public record ChildChunkEmbedding(
        UUID childChunkId,
        UUID documentId,
        String provider,
        String modelName,
        int dimension,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt
) {
}
