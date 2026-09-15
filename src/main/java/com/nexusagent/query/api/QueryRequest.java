package com.nexusagent.query.api;

import java.util.List;
import java.util.UUID;

public record QueryRequest(
        String sessionId,
        String question,
        List<UUID> documentIds,
        Integer topK,
        Integer contextBudgetChars,
        Boolean debug,
        String scope,
        List<ConversationTurn> history
) {
    public QueryRequest(String sessionId, String question, List<UUID> documentIds, Integer topK,
                        Integer contextBudgetChars, Boolean debug, String scope) {
        this(sessionId, question, documentIds, topK, contextBudgetChars, debug, scope, null);
    }
    public QueryRequest(String sessionId, String question, List<UUID> documentIds, Integer topK,
                        Integer contextBudgetChars, Boolean debug) {
        this(sessionId, question, documentIds, topK, contextBudgetChars, debug, null);
    }
}
