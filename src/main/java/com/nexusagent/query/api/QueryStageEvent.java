package com.nexusagent.query.api;

import java.time.Instant;
import java.util.Map;
import com.fasterxml.jackson.annotation.JsonInclude;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record QueryStageEvent(long sequence, String stage, int attempt, String status, Instant timestamp,
                              long durationMs, Map<String, Object> summary) { }
