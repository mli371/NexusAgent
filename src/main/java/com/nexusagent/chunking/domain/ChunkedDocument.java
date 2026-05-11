package com.nexusagent.chunking.domain;

import java.util.List;
import java.util.UUID;

public record ChunkedDocument(
        UUID documentId,
        List<ParentChunk> parentChunks,
        List<ChildChunk> childChunks
) {
}
