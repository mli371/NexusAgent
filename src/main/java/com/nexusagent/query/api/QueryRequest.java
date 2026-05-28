package com.nexusagent.query.api;

import java.util.List;
import java.util.UUID;

public record QueryRequest(
        String sessionId,
        String question,
        List<UUID> documentIds,
        Integer topK,
        Integer contextBudgetChars,
        Boolean debug
) {
}
