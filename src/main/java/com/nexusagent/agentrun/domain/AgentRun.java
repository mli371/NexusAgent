package com.nexusagent.agentrun.domain;

import java.time.OffsetDateTime;
import java.util.UUID;

import com.fasterxml.jackson.databind.JsonNode;
import com.nexusagent.common.context.RequestContext;

public record AgentRun(
        UUID id, String tenantId, String actorId, String traceId, String question,
        String executionMode, String provider, String model, String status,
        String requestHash, String claimHash, int attempt, OffsetDateTime leaseExpiresAt,
        OffsetDateTime deadlineAt, int toolCount, int roundCount, long eventSequence, JsonNode report,
        String errorCode, OffsetDateTime createdAt, OffsetDateTime updatedAt,
        int recoveryCount, OffsetDateTime cancelRequestedAt
) {
    public RequestContext context() {
        return new RequestContext(tenantId, actorId);
    }

    public boolean running() {
        return "RUNNING".equals(status);
    }

    public boolean terminal() {
        return java.util.Set.of("SUCCEEDED", "FAILED", "CANCELLED").contains(status);
    }

    public boolean cancellationRequested() { return cancelRequestedAt != null; }
}
