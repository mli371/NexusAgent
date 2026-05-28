package com.nexusagent.enterprise.audit;

import java.time.OffsetDateTime;
import java.util.UUID;

public record AuditEvent(
        UUID id,
        String tenantId,
        String actorId,
        String traceId,
        AuditEventType eventType,
        String resourceType,
        UUID resourceId,
        UUID documentId,
        String metadataJson,
        OffsetDateTime createdAt
) {
}
