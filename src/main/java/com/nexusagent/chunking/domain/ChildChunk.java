package com.nexusagent.chunking.domain;

import java.time.OffsetDateTime;
import java.util.UUID;

public record ChildChunk(
        UUID id,
        UUID documentId,
        UUID parentChunkId,
        int chunkIndex,
        String text,
        int charStart,
        int charEnd,
        int tokenCount,
        OffsetDateTime createdAt
) {
}
