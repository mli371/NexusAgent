package com.nexusagent.embeddings.repository;

import com.nexusagent.embeddings.domain.EmbeddingVector;
import com.nexusagent.embeddings.domain.VectorSearchResult;
import io.r2dbc.spi.Row;
import io.r2dbc.spi.RowMetadata;
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
        return databaseClient.sql("""
                        WITH query_embedding AS (
                            SELECT CAST(:queryEmbedding AS vector) AS embedding
                        )
                        SELECT
                            c.id AS child_chunk_id,
                            c.document_id,
                            c.parent_chunk_id,
                            c.chunk_index,
                            c.text,
                            e.embedding <=> query_embedding.embedding AS distance
                        FROM child_chunk_embeddings e
                        JOIN child_chunks c ON c.id = e.child_chunk_id
                        CROSS JOIN query_embedding
                        ORDER BY e.embedding <=> query_embedding.embedding
                        LIMIT :limit
                        """)
                .bind("queryEmbedding", queryEmbedding.toPgVectorLiteral())
                .bind("limit", limit)
                .map(this::mapResult)
                .all();
    }

    private VectorSearchResult mapResult(Row row, RowMetadata rowMetadata) {
        return new VectorSearchResult(
                row.get("child_chunk_id", UUID.class),
                row.get("document_id", UUID.class),
                row.get("parent_chunk_id", UUID.class),
                requireInteger(row, "chunk_index"),
                row.get("text", String.class),
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
