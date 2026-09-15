package com.nexusagent.agentrun.tools;

import java.util.List;
import java.util.UUID;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.nexusagent.agentrun.application.RunException;
import com.nexusagent.agentrun.application.RunJson;
import com.nexusagent.chunking.application.DocumentTextExtractor;
import com.nexusagent.common.context.RequestContext;
import com.nexusagent.documents.domain.DocumentMetadata;
import com.nexusagent.documents.repository.DocumentRepository;
import com.nexusagent.embeddings.application.EmbeddingService;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

@Service
public class DocumentDiagnosticsService {
    private final DocumentRepository documents;
    private final DatabaseClient db;
    private final List<DocumentTextExtractor> extractors;
    private final EmbeddingService embedding;
    private final RunJson json;

    public DocumentDiagnosticsService(DocumentRepository documents, DatabaseClient db,
                                      List<DocumentTextExtractor> extractors, EmbeddingService embedding, RunJson json) {
        this.documents = documents;
        this.db = db;
        this.extractors = extractors;
        this.embedding = embedding;
        this.json = json;
    }

    public Mono<DocumentMetadata> accessible(UUID id, RequestContext context) {
        return documents.findById(id, context).switchIfEmpty(Mono.error(RunException.missing()));
    }

    public Mono<JsonNode> inspect(UUID id, RequestContext context) {
        return accessible(id, context).flatMap(document -> {
            var model = embedding.modelInfo();
            return db.sql("""
                    SELECT
                        (SELECT count(*) FROM parent_chunks WHERE document_id=:id) AS parents,
                        (SELECT count(*) FROM child_chunks WHERE document_id=:id) AS children,
                        (SELECT count(*) FROM child_chunk_embeddings WHERE document_id=:id) AS embeddings,
                        (SELECT count(*) FROM child_chunk_embeddings WHERE document_id=:id
                            AND (provider<>:provider OR model_name<>:model OR dimension<>:dimension)) AS mismatched
                    """).bind("id", id).bind("provider", model.provider()).bind("model", model.modelName())
                    .bind("dimension", model.dimension()).map(row -> {
                        boolean supported = extractors.stream().anyMatch(extractor -> extractor.supports(document));
                        long children = row.get("children", Long.class);
                        long embeddings = row.get("embeddings", Long.class);
                        long mismatched = row.get("mismatched", Long.class);
                        String condition = !supported ? "UNSUPPORTED_TYPE" : children == 0 ? "CHUNKING_REQUIRED"
                                : mismatched > 0 ? "EMBEDDING_MODEL_MISMATCH"
                                : embeddings < children ? "EMBEDDING_INCOMPLETE" : "HEALTHY";
                        return (JsonNode) json.object().put("documentId", id.toString())
                                .put("documentStatus", document.status().name()).put("extractionSupported", supported)
                                .put("parentChunkCount", row.get("parents", Long.class)).put("childChunkCount", children)
                                .put("embeddedChildChunkCount", embeddings).put("mismatchedEmbeddingCount", mismatched)
                                .put("embeddingProvider", model.provider()).put("embeddingModel", model.modelName())
                                .put("embeddingDimension", model.dimension()).put("condition", condition);
                    }).one();
        });
    }

    public Mono<JsonNode> jobs(UUID id, RequestContext context) {
        return accessible(id, context).thenMany(db.sql("""
                        SELECT id, job_type, status, started_at, finished_at, created_at, error_code
                        FROM ingestion_jobs WHERE document_id=:id AND tenant_id=:tenant
                        ORDER BY created_at DESC, id DESC LIMIT 11
                        """).bind("id", id).bind("tenant", context.tenantId()).map(row -> {
                            ObjectNode job = json.object().put("id", row.get("id", UUID.class).toString())
                                    .put("jobType", row.get("job_type", String.class))
                                    .put("status", row.get("status", String.class));
                            for (String field : List.of("started_at", "finished_at", "created_at")) {
                                var time = row.get(field, java.time.OffsetDateTime.class);
                                job.put(field, time == null ? null : time.toString());
                            }
                            if ("FAILED".equals(job.get("status").asText())) {
                                String code = row.get("error_code", String.class);
                                job.put("errorCategory", code == null ? "UNKNOWN" : code);
                            }
                            return job;
                        }).all()).collectList().map(jobs -> {
                            ObjectNode data = json.object().put("documentId", id.toString()).put("truncated", jobs.size() > 10);
                            data.set("jobs", json.tree(jobs.stream().limit(10).toList()));
                            return data;
                        });
    }
}
