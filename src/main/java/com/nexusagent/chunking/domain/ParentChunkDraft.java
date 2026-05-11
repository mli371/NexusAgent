package com.nexusagent.chunking.domain;

import java.util.UUID;

public record ParentChunkDraft(
        UUID id,
        int chunkIndex,
        String text,
        int charStart,
        int charEnd,
        int tokenCount
) {
}
