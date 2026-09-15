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
        String retrievalCacheStatus,
        String answerStatus,
        String answerProvider,
        String answerModel,
        List<QueryStageEvent> stages,
        QueryScopeSummary scope,
        QueryResolutionDebug queryResolution
) {
    public QueryResponse(String traceId, String answer, List<Citation> citations, String finalContextText,
                         RetrievalDebugResponse retrievalDebug, ContextDebugResponse contextDebug,
                         List<String> limitations, String retrievalCacheStatus, String answerStatus,
                         String answerProvider, String answerModel, List<QueryStageEvent> stages, QueryScopeSummary scope) {
        this(traceId, answer, citations, finalContextText, retrievalDebug, contextDebug, limitations, retrievalCacheStatus,
                answerStatus, answerProvider, answerModel, stages, scope, null);
    }
    public QueryResponse(String traceId, String answer, List<Citation> citations, String finalContextText,
                         RetrievalDebugResponse retrievalDebug, ContextDebugResponse contextDebug,
                         List<String> limitations, String retrievalCacheStatus) {
        this(traceId, answer, citations, finalContextText, retrievalDebug, contextDebug, limitations,
                retrievalCacheStatus, null, null, null, null, null);
    }
}
