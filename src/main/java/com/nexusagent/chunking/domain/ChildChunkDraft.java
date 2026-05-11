package com.nexusagent.chunking.domain;

import java.util.UUID;

public record ChildChunkDraft(
        UUID id,
        UUID parentChunkId,
        int chunkIndex,
        String text,
        int charStart,
        int charEnd,
        int tokenCount
) {
}
