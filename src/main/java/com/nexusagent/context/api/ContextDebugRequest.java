package com.nexusagent.context.api;

import java.util.List;
import java.util.UUID;

public record ContextDebugRequest(
        String query,
        List<UUID> documentIds,
        Integer topK,
        Integer contextBudgetChars
) {
}
