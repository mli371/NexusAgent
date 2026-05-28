package com.nexusagent.query.api;

import com.fasterxml.jackson.annotation.JsonInclude;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record QueryStreamEvent(
        String type,
        String traceId,
        String message,
        QueryResponse response
) {
}
