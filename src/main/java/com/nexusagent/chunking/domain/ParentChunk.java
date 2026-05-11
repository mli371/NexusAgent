package com.nexusagent.chunking.domain;

import java.time.OffsetDateTime;
import java.util.UUID;

public record ParentChunk(
        UUID id,
        UUID documentId,
        int chunkIndex,
        String text,
        int charStart,
        int charEnd,
        int tokenCount,
        OffsetDateTime createdAt
) {
}
