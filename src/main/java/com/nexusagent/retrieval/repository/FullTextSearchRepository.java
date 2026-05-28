package com.nexusagent.retrieval.repository;

import java.util.List;
import java.util.UUID;

import com.nexusagent.common.context.RequestContext;
import com.nexusagent.retrieval.domain.FullTextSearchResult;
import io.r2dbc.spi.Row;
import io.r2dbc.spi.RowMetadata;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Flux;

@Repository
public class FullTextSearchRepository {

    private final DatabaseClient databaseClient;

    public FullTextSearchRepository(DatabaseClient databaseClient) {
        this.databaseClient = databaseClient;
    }

    public Flux<FullTextSearchResult> search(String query, List<UUID> documentIds, int limit) {
        return search(query, documentIds, limit, RequestContext.defaults());
    }

    public Flux<FullTextSearchResult> search(
            String query,
            List<UUID> documentIds,
            int limit,
            RequestContext context
    ) {
        RequestContext effectiveContext = context == null ? RequestContext.defaults() : context;
        String documentFilter = documentFilter(documentIds);
        String sql = """
                        WITH query_text AS (
                            SELECT websearch_to_tsquery('english', :query) AS query
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
                            ts_rank_cd(to_tsvector('english', c.text), query_text.query) AS score
                        FROM child_chunks c
                        JOIN documents d ON d.id = c.document_id
                        CROSS JOIN query_text
                        WHERE to_tsvector('english', c.text) @@ query_text.query
                          AND d.tenant_id = :tenantId
                          AND (d.visibility = 'TENANT' OR d.owner_id = :actorId)
                        %s
                        ORDER BY score DESC, c.chunk_index ASC
                        LIMIT :limit
                        """.formatted(documentFilter);

        DatabaseClient.GenericExecuteSpec spec = databaseClient.sql(sql)
                .bind("query", query)
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

    private FullTextSearchResult mapResult(Row row, RowMetadata rowMetadata) {
        return new FullTextSearchResult(
                row.get("child_chunk_id", UUID.class),
                row.get("document_id", UUID.class),
                row.get("parent_chunk_id", UUID.class),
                requireInteger(row, "chunk_index"),
                row.get("preview_text", String.class),
                requireDouble(row, "score")
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
