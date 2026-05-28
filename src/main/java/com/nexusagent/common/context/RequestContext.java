package com.nexusagent.common.context;

import com.nexusagent.common.error.BadRequestException;

public record RequestContext(
        String tenantId,
        String actorId
) {

    public static final String TENANT_HEADER = "X-Tenant-Id";
    public static final String ACTOR_HEADER = "X-Actor-Id";
    public static final String TRACE_HEADER = "X-Trace-Id";
    public static final String DEFAULT_TENANT_ID = "default";
    public static final String DEFAULT_ACTOR_ID = "anonymous";

    public static RequestContext defaults() {
        return new RequestContext(DEFAULT_TENANT_ID, DEFAULT_ACTOR_ID);
    }

    public static RequestContext fromHeaders(String tenantId, String actorId) {
        return new RequestContext(
                normalizeHeader(tenantId, DEFAULT_TENANT_ID, "tenant id"),
                normalizeHeader(actorId, DEFAULT_ACTOR_ID, "actor id")
        );
    }

    private static String normalizeHeader(String value, String defaultValue, String label) {
        if (value == null || value.isBlank()) {
            return defaultValue;
        }
        String normalized = value.trim();
        if (normalized.length() > 120) {
            throw new BadRequestException(label + " must be 120 characters or fewer");
        }
        if (!normalized.matches("[A-Za-z0-9._:-]+")) {
            throw new BadRequestException(label + " contains unsupported characters");
        }
        return normalized;
    }
}
