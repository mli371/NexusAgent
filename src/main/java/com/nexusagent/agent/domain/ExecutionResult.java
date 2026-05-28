package com.nexusagent.agent.domain;

import java.util.List;

public record ExecutionResult(
        String traceId,
        List<PlanAction> executedActions,
        boolean retrievalUsed,
        boolean fallbackUsed,
        String answerSource,
        String retrievalCacheStatus,
        int citationCount,
        List<String> notes
) {

    public ExecutionResult {
        executedActions = List.copyOf(executedActions);
        notes = List.copyOf(notes);
    }
}
