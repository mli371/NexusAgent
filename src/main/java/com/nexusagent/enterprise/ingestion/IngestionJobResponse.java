package com.nexusagent.enterprise.ingestion;

import java.time.OffsetDateTime;
import java.util.UUID;

public record IngestionJobResponse(
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

    public static IngestionJobResponse from(IngestionJob job) {
        return new IngestionJobResponse(
                job.id(),
                job.documentId(),
                job.tenantId(),
                job.jobType(),
                job.status(),
                job.errorMessage(),
                job.startedAt(),
                job.finishedAt(),
                job.createdAt(),
                job.updatedAt()
        );
    }
}
