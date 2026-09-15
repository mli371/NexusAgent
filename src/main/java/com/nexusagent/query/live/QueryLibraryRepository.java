package com.nexusagent.query.live;

import java.util.UUID;
import com.nexusagent.common.context.RequestContext;
import com.nexusagent.embeddings.domain.EmbeddingCoverage;
import com.nexusagent.embeddings.domain.EmbeddingModelInfo;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Flux;

@Repository
public class QueryLibraryRepository {
    public static final int MAX_DOCUMENTS = 200;
    private final DatabaseClient database;

    public QueryLibraryRepository(DatabaseClient database) { this.database = database; }

    Flux<DocumentCoverage> visibleCoverage(RequestContext identity, EmbeddingModelInfo model) {
        // One extra document detects overflow. Reject it rather than silently searching a truncated library.
        return database.sql("""
                WITH visible AS (
                    SELECT id FROM documents
                    WHERE tenant_id = :tenant AND (visibility = 'TENANT' OR owner_id = :actor)
                    ORDER BY id LIMIT :limit
                )
                SELECT d.id, COUNT(c.id) AS children, COUNT(e.child_chunk_id) AS embedded,
                    COUNT(e.child_chunk_id) FILTER (WHERE e.provider = :provider
                        AND e.model_name = :model AND e.dimension = :dimension) AS matching
                FROM visible d
                LEFT JOIN child_chunks c ON c.document_id = d.id
                LEFT JOIN child_chunk_embeddings e ON e.child_chunk_id = c.id
                GROUP BY d.id ORDER BY d.id
                """).bind("tenant", identity.tenantId()).bind("actor", identity.actorId())
                .bind("limit", MAX_DOCUMENTS + 1).bind("provider", model.provider())
                .bind("model", model.modelName()).bind("dimension", model.dimension())
                .map(row -> new DocumentCoverage(row.get("id", UUID.class), new EmbeddingCoverage(
                        Math.toIntExact(row.get("children", Long.class)), Math.toIntExact(row.get("embedded", Long.class)),
                        Math.toIntExact(row.get("matching", Long.class))))).all();
    }

    record DocumentCoverage(UUID documentId, EmbeddingCoverage coverage) { }
}
