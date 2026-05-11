package com.nexusagent.chunking.repository;

import java.time.OffsetDateTime;
import java.util.UUID;

import com.nexusagent.chunking.domain.ChildChunk;
import com.nexusagent.chunking.domain.ChildChunkDraft;
import com.nexusagent.chunking.domain.ChunkedDocument;
import com.nexusagent.chunking.domain.ParentChildChunkPlan;
import com.nexusagent.chunking.domain.ParentChunk;
import com.nexusagent.chunking.domain.ParentChunkDraft;
import io.r2dbc.spi.Row;
import io.r2dbc.spi.RowMetadata;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@Repository
public class ChunkRepository {

    private final DatabaseClient databaseClient;

    public ChunkRepository(DatabaseClient databaseClient) {
        this.databaseClient = databaseClient;
    }

    @Transactional
    public Mono<ChunkedDocument> replaceChunks(UUID documentId, ParentChildChunkPlan plan, OffsetDateTime createdAt) {
        return deleteByDocumentId(documentId)
                .thenMany(Flux.fromIterable(plan.parentChunks()))
                .concatMap(parent -> insertParent(documentId, parent, createdAt))
                .thenMany(Flux.fromIterable(plan.childChunks()))
                .concatMap(child -> insertChild(documentId, child, createdAt))
                .then(findByDocumentId(documentId));
    }

    public Mono<ChunkedDocument> findByDocumentId(UUID documentId) {
        Mono<java.util.List<ParentChunk>> parents = findParentsByDocumentId(documentId).collectList();
        Mono<java.util.List<ChildChunk>> children = findChildrenByDocumentId(documentId).collectList();
        return Mono.zip(parents, children)
                .map(tuple -> new ChunkedDocument(documentId, tuple.getT1(), tuple.getT2()));
    }

    private Mono<Void> deleteByDocumentId(UUID documentId) {
        return databaseClient.sql("DELETE FROM child_chunks WHERE document_id = :documentId")
                .bind("documentId", documentId)
                .fetch()
                .rowsUpdated()
                .then(databaseClient.sql("DELETE FROM parent_chunks WHERE document_id = :documentId")
                        .bind("documentId", documentId)
                        .fetch()
                        .rowsUpdated())
                .then();
    }

    private Mono<Long> insertParent(UUID documentId, ParentChunkDraft parent, OffsetDateTime createdAt) {
        return databaseClient.sql("""
                        INSERT INTO parent_chunks (
                            id,
                            document_id,
                            chunk_index,
                            text,
                            char_start,
                            char_end,
                            token_count,
                            created_at
                        )
                        VALUES (
                            :id,
                            :documentId,
                            :chunkIndex,
                            :text,
                            :charStart,
                            :charEnd,
                            :tokenCount,
                            :createdAt
                        )
                        """)
                .bind("id", parent.id())
                .bind("documentId", documentId)
                .bind("chunkIndex", parent.chunkIndex())
                .bind("text", parent.text())
                .bind("charStart", parent.charStart())
                .bind("charEnd", parent.charEnd())
                .bind("tokenCount", parent.tokenCount())
                .bind("createdAt", createdAt)
                .fetch()
                .rowsUpdated();
    }

    private Mono<Long> insertChild(UUID documentId, ChildChunkDraft child, OffsetDateTime createdAt) {
        return databaseClient.sql("""
                        INSERT INTO child_chunks (
                            id,
                            document_id,
                            parent_chunk_id,
                            chunk_index,
                            text,
                            char_start,
                            char_end,
                            token_count,
                            created_at
                        )
                        VALUES (
                            :id,
                            :documentId,
                            :parentChunkId,
                            :chunkIndex,
                            :text,
                            :charStart,
                            :charEnd,
                            :tokenCount,
                            :createdAt
                        )
                        """)
                .bind("id", child.id())
                .bind("documentId", documentId)
                .bind("parentChunkId", child.parentChunkId())
                .bind("chunkIndex", child.chunkIndex())
                .bind("text", child.text())
                .bind("charStart", child.charStart())
                .bind("charEnd", child.charEnd())
                .bind("tokenCount", child.tokenCount())
                .bind("createdAt", createdAt)
                .fetch()
                .rowsUpdated();
    }

    private Flux<ParentChunk> findParentsByDocumentId(UUID documentId) {
        return databaseClient.sql("""
                        SELECT
                            id,
                            document_id,
                            chunk_index,
                            text,
                            char_start,
                            char_end,
                            token_count,
                            created_at
                        FROM parent_chunks
                        WHERE document_id = :documentId
                        ORDER BY chunk_index
                        """)
                .bind("documentId", documentId)
                .map(this::mapParent)
                .all();
    }

    private Flux<ChildChunk> findChildrenByDocumentId(UUID documentId) {
        return databaseClient.sql("""
                        SELECT
                            id,
                            document_id,
                            parent_chunk_id,
                            chunk_index,
                            text,
                            char_start,
                            char_end,
                            token_count,
                            created_at
                        FROM child_chunks
                        WHERE document_id = :documentId
                        ORDER BY chunk_index
                        """)
                .bind("documentId", documentId)
                .map(this::mapChild)
                .all();
    }

    private ParentChunk mapParent(Row row, RowMetadata rowMetadata) {
        return new ParentChunk(
                row.get("id", UUID.class),
                row.get("document_id", UUID.class),
                requireInteger(row, "chunk_index"),
                row.get("text", String.class),
                requireInteger(row, "char_start"),
                requireInteger(row, "char_end"),
                requireInteger(row, "token_count"),
                row.get("created_at", OffsetDateTime.class)
        );
    }

    private ChildChunk mapChild(Row row, RowMetadata rowMetadata) {
        return new ChildChunk(
                row.get("id", UUID.class),
                row.get("document_id", UUID.class),
                row.get("parent_chunk_id", UUID.class),
                requireInteger(row, "chunk_index"),
                row.get("text", String.class),
                requireInteger(row, "char_start"),
                requireInteger(row, "char_end"),
                requireInteger(row, "token_count"),
                row.get("created_at", OffsetDateTime.class)
        );
    }

    private int requireInteger(Row row, String columnName) {
        Integer value = row.get(columnName, Integer.class);
        if (value == null) {
            throw new IllegalStateException("Missing required integer column: " + columnName);
        }
        return value;
    }
}
