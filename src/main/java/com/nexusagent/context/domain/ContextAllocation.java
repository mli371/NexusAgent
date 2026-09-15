package com.nexusagent.context.domain;

import java.util.UUID;

/** Bounded diagnostics for one ranked candidate; never contains source text. */
public record ContextAllocation(
        UUID documentId, String originalFilename, UUID parentChunkId, UUID childChunkId,
        int rerankedRank, Status status, int childChars, int parentChars, int allocatedChars,
        boolean parentTruncated
) {
    public enum Status { INCLUDED, DUPLICATE_PARENT, CHILD_EXCEEDS_REMAINING_BUDGET }
}
