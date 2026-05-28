package com.nexusagent.enterprise.ingestion;

import java.time.OffsetDateTime;
import java.util.UUID;

public record IngestionJob(
        UUID id,
        UUID documentId,
        String tenantId,
        IngestionJobType jobType,
        IngestionJobStatus status,
        String errorMessage,
        OffsetDateTime startedAt,
        OffsetDateTime finishedAt,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt
) {
}
