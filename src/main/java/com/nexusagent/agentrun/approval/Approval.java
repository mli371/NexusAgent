package com.nexusagent.agentrun.approval;

import java.time.OffsetDateTime;
import java.util.UUID;
import com.fasterxml.jackson.databind.JsonNode;

public record Approval(UUID id, UUID runId, UUID documentId, String action, JsonNode arguments,
                       String fingerprint, String reason, String status, String requestedBy,
                       String decidedBy, OffsetDateTime expiresAt, OffsetDateTime decidedAt) { }
