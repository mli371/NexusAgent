package com.nexusagent.context.domain;

import java.util.UUID;

public record Citation(
        int citationIndex,
        String citationMarker,
        UUID documentId,
        String originalFilename,
        UUID parentChunkId,
        UUID childChunkId,
        int chunkIndex,
        String sectionTitle,
        int charStart,
        int charEnd,
        String previewText
) {
}
