package com.nexusagent.retrieval.api;

import java.util.List;
import java.util.UUID;

public record RetrievalDebugRequest(
        String query,
        List<UUID> documentIds,
        Integer topK
) {
}
