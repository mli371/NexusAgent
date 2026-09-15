package com.nexusagent.agentrun.domain;

import java.util.UUID;

import com.fasterxml.jackson.databind.JsonNode;

public record ToolCall(UUID id, UUID runId, UUID invocationId, String toolName,
                       String argumentsHash, JsonNode arguments, String status, JsonNode result) {
}
