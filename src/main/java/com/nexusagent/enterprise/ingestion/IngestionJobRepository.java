package com.nexusagent.enterprise.ingestion;

import java.time.OffsetDateTime;
import java.util.UUID;

import io.r2dbc.spi.Row;
import io.r2dbc.spi.RowMetadata;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@Repository
public class IngestionJobRepository {

    private final DatabaseClient databaseClient;

    public IngestionJobRepository(DatabaseClient databaseClient) {
        this.databaseClient = databaseClient;
    }

    public Mono<IngestionJob> createRunning(
            UUID documentId,
            String tenantId,
            IngestionJobType jobType,
            OffsetDateTime timestamp
    ) {
        UUID jobId = UUID.randomUUID();
        return databaseClient.sql("""
                        INSERT INTO ingestion_jobs (
                            id,
                            document_id,
                            tenant_id,
                            job_type,
                            status,
                            error_message,
                            started_at,
                            finished_at,
                            created_at,
                            updated_at
                        )
                        VALUES (
                            :id,
                            :documentId,
                            :tenantId,
                            :jobType,
                            :status,
                            NULL,
                            :startedAt,
                            NULL,
                            :createdAt,
                            :updatedAt
                        )
                        RETURNING *
                        """)
                .bind("id", jobId)
                .bind("documentId", documentId)
                .bind("tenantId", tenantId)
                .bind("jobType", jobType.name())
                .bind("status", IngestionJobStatus.RUNNING.name())
                .bind("startedAt", timestamp)
                .bind("createdAt", timestamp)
                .bind("updatedAt", timestamp)
                .map(this::mapRow)
                .one();
    }

    public Mono<IngestionJob> markSucceeded(UUID jobId, OffsetDateTime timestamp) {
        return databaseClient.sql("""
                        UPDATE ingestion_jobs
                        SET status = :status,
                            finished_at = :finishedAt,
                            updated_at = :updatedAt
                        WHERE id = :id
                        RETURNING *
                        """)
                .bind("status", IngestionJobStatus.SUCCEEDED.name())
                .bind("finishedAt", timestamp)
                .bind("updatedAt", timestamp)
                .bind("id", jobId)
                .map(this::mapRow)
                .one();
    }

    public Mono<IngestionJob> markFailed(UUID jobId, String errorMessage, OffsetDateTime timestamp) {
        return databaseClient.sql("""
                        UPDATE ingestion_jobs
                        SET status = :status,
                            error_message = :errorMessage,
                            finished_at = :finishedAt,
                            updated_at = :updatedAt
                        WHERE id = :id
                        RETURNING *
                        """)
                .bind("status", IngestionJobStatus.FAILED.name())
                .bind("errorMessage", truncate(errorMessage))
                .bind("finishedAt", timestamp)
                .bind("updatedAt", timestamp)
                .bind("id", jobId)
                .map(this::mapRow)
                .one();
    }

    public Flux<IngestionJob> findByDocumentIdAndTenant(UUID documentId, String tenantId) {
        return databaseClient.sql("""
                        SELECT *
                        FROM ingestion_jobs
                        WHERE document_id = :documentId
                          AND tenant_id = :tenantId
                        ORDER BY created_at DESC
                        """)
                .bind("documentId", documentId)
                .bind("tenantId", tenantId)
                .map(this::mapRow)
                .all();
    }

    private String truncate(String errorMessage) {
        if (errorMessage == null || errorMessage.isBlank()) {
            return "unknown error";
        }
        return errorMessage.length() <= 500 ? errorMessage : errorMessage.substring(0, 500);
    }

    private IngestionJob mapRow(Row row, RowMetadata rowMetadata) {
        return new IngestionJob(
                row.get("id", UUID.class),
                row.get("document_id", UUID.class),
                row.get("tenant_id", String.class),
                IngestionJobType.valueOf(row.get("job_type", String.class)),
                IngestionJobStatus.valueOf(row.get("status", String.class)),
                row.get("error_message", String.class),
                row.get("started_at", OffsetDateTime.class),
                row.get("finished_at", OffsetDateTime.class),
                row.get("created_at", OffsetDateTime.class),
                row.get("updated_at", OffsetDateTime.class)
        );
    }
}
