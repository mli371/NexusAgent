package com.nexusagent.embeddings.repository;

import java.time.OffsetDateTime;
import java.util.UUID;

import com.nexusagent.chunking.domain.ChildChunk;
import com.nexusagent.embeddings.domain.ChildChunkEmbedding;
import com.nexusagent.embeddings.domain.EmbeddingModelInfo;
import com.nexusagent.embeddings.domain.EmbeddingVector;
import io.r2dbc.spi.Row;
import io.r2dbc.spi.RowMetadata;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@Repository
public class ChildChunkEmbeddingRepository {

    private final DatabaseClient databaseClient;

    public ChildChunkEmbeddingRepository(DatabaseClient databaseClient) {
        this.databaseClient = databaseClient;
    }

    public Mono<ChildChunkEmbedding> upsert(
            ChildChunk childChunk,
            EmbeddingVector embedding,
            EmbeddingModelInfo modelInfo,
            OffsetDateTime timestamp
    ) {
        return databaseClient.sql("""
                        INSERT INTO child_chunk_embeddings (
                            child_chunk_id,
                            document_id,
                            embedding,
                            provider,
                            model_name,
                            dimension,
                            created_at,
                            updated_at
                        )
                        VALUES (
                            :childChunkId,
                            :documentId,
                            CAST(:embedding AS vector),
                            :provider,
                            :modelName,
                            :dimension,
                            :createdAt,
                            :updatedAt
                        )
                        ON CONFLICT (child_chunk_id)
                        DO UPDATE SET
                            document_id = EXCLUDED.document_id,
                            embedding = EXCLUDED.embedding,
                            provider = EXCLUDED.provider,
                            model_name = EXCLUDED.model_name,
                            dimension = EXCLUDED.dimension,
                            updated_at = EXCLUDED.updated_at
                        RETURNING
                            child_chunk_id,
                            document_id,
                            provider,
                            model_name,
                            dimension,
                            created_at,
                            updated_at
                        """)
                .bind("childChunkId", childChunk.id())
                .bind("documentId", childChunk.documentId())
                .bind("embedding", embedding.toPgVectorLiteral())
                .bind("provider", modelInfo.provider())
                .bind("modelName", modelInfo.modelName())
                .bind("dimension", modelInfo.dimension())
                .bind("createdAt", timestamp)
                .bind("updatedAt", timestamp)
                .map(this::mapEmbedding)
                .one();
    }

    public Flux<UUID> findEmbeddedChildChunkIds(UUID documentId) {
        return databaseClient.sql("""
                        SELECT child_chunk_id
                        FROM child_chunk_embeddings
                        WHERE document_id = :documentId
                        """)
                .bind("documentId", documentId)
                .map((row, rowMetadata) -> row.get("child_chunk_id", UUID.class))
                .all();
    }

    public Mono<Long> countByDocumentId(UUID documentId) {
        return databaseClient.sql("""
                        SELECT COUNT(*) AS embedding_count
                        FROM child_chunk_embeddings
                        WHERE document_id = :documentId
                        """)
                .bind("documentId", documentId)
                .map((row, rowMetadata) -> requireLong(row, "embedding_count"))
                .one()
                .defaultIfEmpty(0L);
    }

    private ChildChunkEmbedding mapEmbedding(Row row, RowMetadata rowMetadata) {
        return new ChildChunkEmbedding(
                row.get("child_chunk_id", UUID.class),
                row.get("document_id", UUID.class),
                row.get("provider", String.class),
                row.get("model_name", String.class),
                requireInteger(row, "dimension"),
                row.get("created_at", OffsetDateTime.class),
                row.get("updated_at", OffsetDateTime.class)
        );
    }

    private int requireInteger(Row row, String columnName) {
        Integer value = row.get(columnName, Integer.class);
        if (value == null) {
            throw new IllegalStateException("Missing required integer column: " + columnName);
        }
        return value;
    }

    private long requireLong(Row row, String columnName) {
        Long value = row.get(columnName, Long.class);
        if (value != null) {
            return value;
        }

        Number number = row.get(columnName, Number.class);
        if (number == null) {
            throw new IllegalStateException("Missing required numeric column: " + columnName);
        }
        return number.longValue();
    }
}
