package com.nexusagent.context.domain;

import java.util.UUID;

import com.nexusagent.retrieval.domain.RetrievalSource;

public record SelectedChildChunk(
        UUID childChunkId,
        UUID parentChunkId,
        UUID documentId,
        String originalFilename,
        int chunkIndex,
        int charStart,
        int charEnd,
        String previewText,
        RetrievalSource source,
        int rerankedRank,
        double rerankScore,
        String selectionReason
) {
}
