package com.nexusagent.retrieval.domain;

import java.util.UUID;

public record FullTextSearchResult(
        UUID childChunkId,
        UUID documentId,
        UUID parentChunkId,
        int chunkIndex,
        String previewText,
        double score
) {
}
