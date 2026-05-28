package com.nexusagent.query.application;

import java.util.List;
import java.util.UUID;

import com.nexusagent.common.context.RequestContext;

public record QueryCacheKey(
        String tenantId,
        String actorId,
        String question,
        List<UUID> documentIds,
        Integer topK,
        Integer contextBudgetChars
) {

    public QueryCacheKey(
            String question,
            List<UUID> documentIds,
            Integer topK,
            Integer contextBudgetChars
    ) {
        this(
                RequestContext.DEFAULT_TENANT_ID,
                RequestContext.DEFAULT_ACTOR_ID,
                question,
                documentIds,
                topK,
                contextBudgetChars
        );
    }

    public QueryCacheKey {
        tenantId = tenantId == null || tenantId.isBlank() ? RequestContext.DEFAULT_TENANT_ID : tenantId;
        actorId = actorId == null || actorId.isBlank() ? RequestContext.DEFAULT_ACTOR_ID : actorId;
        documentIds = documentIds == null ? List.of() : List.copyOf(documentIds);
    }
}
