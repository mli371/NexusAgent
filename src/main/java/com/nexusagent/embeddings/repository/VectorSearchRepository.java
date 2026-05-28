package com.nexusagent.embeddings.repository;

import com.nexusagent.common.context.RequestContext;
import com.nexusagent.embeddings.domain.EmbeddingVector;
import com.nexusagent.embeddings.domain.VectorSearchResult;
import io.r2dbc.spi.Row;
import io.r2dbc.spi.RowMetadata;
import java.util.List;
import java.util.UUID;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Flux;

@Repository
public class VectorSearchRepository {

    private final DatabaseClient databaseClient;

    public VectorSearchRepository(DatabaseClient databaseClient) {
        this.databaseClient = databaseClient;
    }

    public Flux<VectorSearchResult> search(EmbeddingVector queryEmbedding, int limit) {
        return search(queryEmbedding, List.of(), limit);
    }

    public Flux<VectorSearchResult> search(EmbeddingVector queryEmbedding, List<UUID> documentIds, int limit) {
        return search(queryEmbedding, documentIds, limit, RequestContext.defaults());
    }

    public Flux<VectorSearchResult> search(
            EmbeddingVector queryEmbedding,
            List<UUID> documentIds,
            int limit,
            RequestContext context
    ) {
        RequestContext effectiveContext = context == null ? RequestContext.defaults() : context;
        String documentFilter = documentFilter(documentIds);
        String sql = """
                        WITH query_embedding AS (
                            SELECT CAST(:queryEmbedding AS vector) AS embedding
                        )
                        SELECT
                            c.id AS child_chunk_id,
                            c.document_id,
                            c.parent_chunk_id,
                            c.chunk_index,
                            CASE
                                WHEN length(c.text) > 160 THEN substring(c.text from 1 for 160) || '...'
                                ELSE c.text
                            END AS preview_text,
                            e.embedding <=> query_embedding.embedding AS distance
                        FROM child_chunk_embeddings e
                        JOIN child_chunks c ON c.id = e.child_chunk_id
                        JOIN documents d ON d.id = c.document_id
                        CROSS JOIN query_embedding
                        WHERE d.tenant_id = :tenantId
                          AND (d.visibility = 'TENANT' OR d.owner_id = :actorId)
                        %s
                        ORDER BY e.embedding <=> query_embedding.embedding
                        LIMIT :limit
                        """.formatted(documentFilter);

        DatabaseClient.GenericExecuteSpec spec = databaseClient.sql(sql)
                .bind("queryEmbedding", queryEmbedding.toPgVectorLiteral())
                .bind("limit", limit)
                .bind("tenantId", effectiveContext.tenantId())
                .bind("actorId", effectiveContext.actorId());
        spec = bindDocumentIds(spec, documentIds);
        return spec
                .map(this::mapResult)
                .all();
    }

    private String documentFilter(List<UUID> documentIds) {
        if (documentIds == null || documentIds.isEmpty()) {
            return "";
        }

        StringBuilder builder = new StringBuilder("AND c.document_id IN (");
        for (int index = 0; index < documentIds.size(); index++) {
            if (index > 0) {
                builder.append(", ");
            }
            builder.append(":documentId").append(index);
        }
        builder.append(")");
        return builder.toString();
    }

    private DatabaseClient.GenericExecuteSpec bindDocumentIds(
            DatabaseClient.GenericExecuteSpec spec,
            List<UUID> documentIds
    ) {
        if (documentIds == null) {
            return spec;
        }

        DatabaseClient.GenericExecuteSpec current = spec;
        for (int index = 0; index < documentIds.size(); index++) {
            current = current.bind("documentId" + index, documentIds.get(index));
        }
        return current;
    }

    private VectorSearchResult mapResult(Row row, RowMetadata rowMetadata) {
        return new VectorSearchResult(
                row.get("child_chunk_id", UUID.class),
                row.get("document_id", UUID.class),
                row.get("parent_chunk_id", UUID.class),
                requireInteger(row, "chunk_index"),
                row.get("preview_text", String.class),
                requireDouble(row, "distance")
        );
    }

    private int requireInteger(Row row, String columnName) {
        Integer value = row.get(columnName, Integer.class);
        if (value == null) {
            throw new IllegalStateException("Missing required integer column: " + columnName);
        }
        return value;
    }

    private double requireDouble(Row row, String columnName) {
        Double value = row.get(columnName, Double.class);
        if (value != null) {
            return value;
        }

        Number number = row.get(columnName, Number.class);
        if (number == null) {
            throw new IllegalStateException("Missing required numeric column: " + columnName);
        }
        return number.doubleValue();
    }
}
