package com.nexusagent.query.api;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.nexusagent.context.api.ContextDebugResponse;
import com.nexusagent.context.domain.Citation;
import com.nexusagent.retrieval.api.RetrievalDebugResponse;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record QueryResponse(
        String traceId,
        String answer,
        List<Citation> citations,
        String finalContextText,
        RetrievalDebugResponse retrievalDebug,
        ContextDebugResponse contextDebug,
        List<String> limitations,
        String retrievalCacheStatus
) {
}
