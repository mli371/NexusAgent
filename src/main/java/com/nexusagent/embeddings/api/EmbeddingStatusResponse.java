package com.nexusagent.embeddings.api;

import java.util.UUID;

import com.nexusagent.embeddings.domain.EmbeddingStatus;

public record EmbeddingStatusResponse(
        UUID documentId,
        int childChunkCount,
        int embeddedChildChunkCount,
        int missingChildChunkCount,
        boolean complete,
        String provider,
        String modelName,
        int dimension,
        int matchingChildChunkCount,
        int mismatchedChildChunkCount
) {

    public static EmbeddingStatusResponse from(EmbeddingStatus status) {
        return new EmbeddingStatusResponse(
                status.documentId(),
                status.childChunkCount(),
                status.embeddedChildChunkCount(),
                status.missingChildChunkCount(),
                status.complete(),
                status.provider(),
                status.modelName(),
                status.dimension(),
                status.matchingChildChunkCount(),
                status.mismatchedChildChunkCount()
        );
    }
}
