package com.nexusagent.documents.repository;

import java.time.OffsetDateTime;
import java.util.UUID;

import com.nexusagent.documents.domain.DocumentMetadata;
import com.nexusagent.documents.domain.DocumentStatus;
import io.r2dbc.spi.Row;
import io.r2dbc.spi.RowMetadata;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@Repository
public class DocumentRepository {

    private final DatabaseClient databaseClient;

    public DocumentRepository(DatabaseClient databaseClient) {
        this.databaseClient = databaseClient;
    }

    public Mono<DocumentMetadata> save(DocumentMetadata metadata) {
        return databaseClient.sql("""
                        INSERT INTO documents (
                            id,
                            original_filename,
                            content_type,
                            size_bytes,
                            sha256,
                            minio_bucket,
                            minio_object_key,
                            status,
                            created_at,
                            updated_at
                        )
                        VALUES (
                            :id,
                            :originalFilename,
                            :contentType,
                            :sizeBytes,
                            :sha256,
                            :minioBucket,
                            :minioObjectKey,
                            :status,
                            :createdAt,
                            :updatedAt
                        )
                        """)
                .bind("id", metadata.id())
                .bind("originalFilename", metadata.originalFilename())
                .bind("contentType", metadata.contentType())
                .bind("sizeBytes", metadata.sizeBytes())
                .bind("sha256", metadata.sha256())
                .bind("minioBucket", metadata.minioBucket())
                .bind("minioObjectKey", metadata.minioObjectKey())
                .bind("status", metadata.status().name())
                .bind("createdAt", metadata.createdAt())
                .bind("updatedAt", metadata.updatedAt())
                .fetch()
                .rowsUpdated()
                .thenReturn(metadata);
    }

    public Mono<DocumentMetadata> findById(UUID id) {
        return databaseClient.sql("""
                        SELECT
                            id,
                            original_filename,
                            content_type,
                            size_bytes,
                            sha256,
                            minio_bucket,
                            minio_object_key,
                            status,
                            created_at,
                            updated_at
                        FROM documents
                        WHERE id = :id
                        """)
                .bind("id", id)
                .map(this::mapRow)
                .one();
    }

    public Mono<Void> updateStatus(UUID id, DocumentStatus status, OffsetDateTime updatedAt) {
        return databaseClient.sql("""
                        UPDATE documents
                        SET status = :status,
                            updated_at = :updatedAt
                        WHERE id = :id
                        """)
                .bind("id", id)
                .bind("status", status.name())
                .bind("updatedAt", updatedAt)
                .fetch()
                .rowsUpdated()
                .then();
    }

    public Flux<DocumentMetadata> findAll(int limit, int offset) {
        return databaseClient.sql("""
                        SELECT
                            id,
                            original_filename,
                            content_type,
                            size_bytes,
                            sha256,
                            minio_bucket,
                            minio_object_key,
                            status,
                            created_at,
                            updated_at
                        FROM documents
                        ORDER BY created_at DESC
                        LIMIT :limit OFFSET :offset
                        """)
                .bind("limit", limit)
                .bind("offset", offset)
                .map(this::mapRow)
                .all();
    }

    private DocumentMetadata mapRow(Row row, RowMetadata rowMetadata) {
        return new DocumentMetadata(
                row.get("id", UUID.class),
                row.get("original_filename", String.class),
                row.get("content_type", String.class),
                requireLong(row, "size_bytes"),
                row.get("sha256", String.class),
                row.get("minio_bucket", String.class),
                row.get("minio_object_key", String.class),
                DocumentStatus.valueOf(row.get("status", String.class)),
                row.get("created_at", OffsetDateTime.class),
                row.get("updated_at", OffsetDateTime.class)
        );
    }

    private long requireLong(Row row, String columnName) {
        Long value = row.get(columnName, Long.class);
        if (value == null) {
            throw new IllegalStateException("Missing required numeric column: " + columnName);
        }
        return value;
    }
}
