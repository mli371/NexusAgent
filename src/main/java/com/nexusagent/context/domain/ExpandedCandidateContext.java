package com.nexusagent.context.domain;

import com.nexusagent.chunking.domain.ChildChunk;
import com.nexusagent.chunking.domain.ParentChunk;
import com.nexusagent.documents.domain.DocumentMetadata;

public record ExpandedCandidateContext(
        RerankedCandidate rerankedCandidate,
        DocumentMetadata document,
        ChildChunk childChunk,
        ParentChunk parentChunk
) {
}
