package com.nexusagent.agent.api;

import java.util.List;
import java.util.UUID;

public record AgentQueryRequest(
        String sessionId,
        String question,
        List<UUID> documentIds,
        Integer topK,
        Integer contextBudgetChars,
        Boolean debug
) {
}
