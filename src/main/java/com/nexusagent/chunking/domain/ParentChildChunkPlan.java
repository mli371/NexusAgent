package com.nexusagent.chunking.domain;

import java.util.List;

public record ParentChildChunkPlan(
        List<ParentChunkDraft> parentChunks,
        List<ChildChunkDraft> childChunks
) {
}
