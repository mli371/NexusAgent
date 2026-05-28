package com.nexusagent.context.domain;

import java.util.List;
import java.util.UUID;

public record ExpandedParentContext(
        UUID parentChunkId,
        UUID documentId,
        String originalFilename,
        int parentChunkIndex,
        int charStart,
        int charEnd,
        String text,
        boolean truncated,
        int includedChars,
        List<UUID> childChunkIds
) {

    public ExpandedParentContext {
        childChunkIds = List.copyOf(childChunkIds);
    }
}
