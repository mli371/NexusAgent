package com.nexusagent.agentrun.observation;

import java.time.OffsetDateTime;
import java.util.UUID;

import com.fasterxml.jackson.databind.JsonNode;

public record ToolObservation(UUID id, UUID invocationId, String toolName, int attempt,
                              UUID retryOf, String status, OffsetDateTime createdAt,
                              OffsetDateTime finishedAt, JsonNode arguments, JsonNode result) { }
