package com.nexusagent.agent.api;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.nexusagent.agent.domain.AgentWorkflowStatus;
import com.nexusagent.agent.domain.CritiqueResult;
import com.nexusagent.agent.domain.ExecutionResult;
import com.nexusagent.agent.domain.Plan;
import com.nexusagent.context.domain.Citation;
import com.nexusagent.query.api.QueryResponse;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record AgentQueryResponse(
        String traceId,
        String answer,
        List<Citation> citations,
        AgentWorkflowStatus workflowStatus,
        Plan plan,
        ExecutionResult executionResult,
        CritiqueResult critiqueResult,
        QueryResponse queryDebug
) {

    public AgentQueryResponse {
        citations = List.copyOf(citations);
    }
}
