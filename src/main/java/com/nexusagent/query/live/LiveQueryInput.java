package com.nexusagent.query.live;

import java.util.List;
import java.util.UUID;
import com.nexusagent.common.context.RequestContext;
import com.nexusagent.common.error.BadRequestException;
import com.nexusagent.context.application.ContextProperties;
import com.nexusagent.query.api.QueryRequest;
import com.nexusagent.retrieval.application.RetrievalProperties;

record LiveQueryInput(String traceId, String sessionId, String question, List<UUID> documentIds,
                      int topK, int budget, boolean debug, RequestContext context, String scope) {
    static LiveQueryInput from(QueryRequest request, String traceId, RequestContext context,
                               RetrievalProperties retrieval, ContextProperties contexts) {
        if (request == null || request.question() == null || request.question().isBlank()
                || request.question().length() > 2000) {
            throw new BadRequestException("question must contain 1 to 2000 characters");
        }
        List<UUID> ids = request.documentIds() == null ? List.of() : request.documentIds();
        String scope = request.scope() == null ? (ids.isEmpty() ? "library" : "documents") : request.scope();
        if (!List.of("library", "documents").contains(scope)) {
            throw new BadRequestException("scope must be library or documents");
        }
        if (scope.equals("library") && !ids.isEmpty()) {
            throw new BadRequestException("library scope must not include documentIds");
        }
        if (scope.equals("documents") && (ids.isEmpty() || ids.size() > 10 || ids.stream().anyMatch(id -> id == null))) {
            throw new BadRequestException("documents scope requires 1 to 10 explicitly selected documentIds");
        }
        int topK = request.topK() == null ? retrieval.getDefaultTopK() : request.topK();
        int budget = request.contextBudgetChars() == null ? contexts.getDefaultBudgetChars() : request.contextBudgetChars();
        if (topK < 1 || topK > retrieval.getMaxTopK()) {
            throw new BadRequestException("topK must be between 1 and " + retrieval.getMaxTopK());
        }
        if (budget < 1 || budget > contexts.getMaxBudgetChars()) {
            throw new BadRequestException("contextBudgetChars must be between 1 and " + contexts.getMaxBudgetChars());
        }
        validIdentifier(traceId, "traceId");
        String sessionId = request.sessionId() == null || request.sessionId().isBlank() ? traceId : request.sessionId().trim();
        validIdentifier(sessionId, "sessionId");
        RequestContext effective = context == null ? RequestContext.defaults()
                : RequestContext.fromHeaders(context.tenantId(), context.actorId());
        return new LiveQueryInput(traceId, sessionId, request.question().trim(),
                ids.stream().distinct().toList(), topK, budget,
                Boolean.TRUE.equals(request.debug()), effective, scope);
    }

    LiveQueryInput withDocuments(List<UUID> ids) {
        return new LiveQueryInput(traceId, sessionId, question, List.copyOf(ids), topK, budget, debug, context, scope);
    }

    private static void validIdentifier(String value, String name) {
        if (value.length() > 120 || !value.matches("[A-Za-z0-9._:-]+")) {
            throw new BadRequestException(name + " must be at most 120 letters, digits or . _ : -");
        }
    }
}
