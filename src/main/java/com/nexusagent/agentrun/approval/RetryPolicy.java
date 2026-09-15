package com.nexusagent.agentrun.approval;

import java.util.Set;
import java.util.UUID;

import com.fasterxml.jackson.databind.JsonNode;
import com.nexusagent.agentrun.application.RunException;
import com.nexusagent.agentrun.application.RunJson;
import com.nexusagent.agentrun.domain.AgentRun;
import com.nexusagent.agentrun.tools.DocumentDiagnosticsService;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

@Service
public class RetryPolicy {
    private final DocumentDiagnosticsService diagnostics;
    private final DatabaseClient db;
    private final RunJson json;

    public RetryPolicy(DocumentDiagnosticsService diagnostics, DatabaseClient db, RunJson json) {
        this.diagnostics = diagnostics;
        this.db = db;
        this.json = json;
    }

    public JsonNode arguments(UUID document, String action) {
        if (!Set.of("CHUNK", "EMBED_MISSING").contains(action)) { throw RunException.invalid("Unsupported retry action"); }
        var args = json.object().put("documentId", document.toString()).put("action", action);
        if (action.equals("CHUNK")) { args.put("force", false); } else { args.put("missingOnly", true); }
        return args;
    }

    public Mono<State> inspect(AgentRun run, UUID document, String action) {
        JsonNode arguments = arguments(document, action);
        return diagnostics.accessible(document, run.context()).flatMap(metadata ->
                diagnostics.inspect(document, run.context()).flatMap(counts -> db.sql("""
                    SELECT
                      (SELECT count(*) FROM ingestion_jobs WHERE document_id=:id AND status IN ('RUNNING','PENDING')) AS active,
                      (SELECT status FROM ingestion_jobs WHERE document_id=:id
                        AND job_type IN (:type, :alternate) ORDER BY created_at DESC, id DESC LIMIT 1) AS latest_status,
                      (SELECT error_code FROM ingestion_jobs WHERE document_id=:id
                        AND job_type IN (:type, :alternate) ORDER BY created_at DESC, id DESC LIMIT 1) AS latest_error,
                      (SELECT encode(sha256(convert_to(COALESCE(string_agg(id::text, ',' ORDER BY id), ''), 'UTF8')), 'hex')
                        FROM parent_chunks WHERE document_id=:id) AS parents_hash,
                      (SELECT encode(sha256(convert_to(COALESCE(string_agg(id::text, ',' ORDER BY id), ''), 'UTF8')), 'hex')
                        FROM child_chunks WHERE document_id=:id) AS children_hash,
                      (SELECT encode(sha256(convert_to(COALESCE(string_agg(
                        concat_ws(':', id, job_type, status, error_code, updated_at), ',' ORDER BY id), ''), 'UTF8')), 'hex')
                        FROM ingestion_jobs WHERE document_id=:id) AS jobs_hash,
                      (SELECT encode(sha256(convert_to(COALESCE(string_agg(
                        concat_ws(':', child_chunk_id, provider, model_name, dimension, updated_at), ',' ORDER BY child_chunk_id), ''), 'UTF8')), 'hex')
                        FROM child_chunk_embeddings WHERE document_id=:id) AS embeddings_hash
                    """).bind("id", document).bind("type", action.equals("CHUNK") ? "CHUNK" : "EMBED")
                        .bind("alternate", action.equals("CHUNK") ? "FORCE_RECHUNK" : "REEMBED").map(row -> {
                            var state = json.object();
                            state.set("arguments", arguments);
                            state.set("metadata", json.tree(metadata));
                            state.set("counts", counts);
                            for (String key : java.util.List.of("parents_hash", "children_hash", "jobs_hash", "embeddings_hash")) {
                                state.put(key, row.get(key, String.class));
                            }
                            String denial = null;
                            if (!counts.path("extractionSupported").asBoolean()) { denial = "UNSUPPORTED_TYPE"; }
                            else if (counts.path("mismatchedEmbeddingCount").asLong() > 0) { denial = "EMBEDDING_MODEL_MISMATCH"; }
                            else if (row.get("active", Long.class) > 0) { denial = "INGESTION_ACTIVE"; }
                            long children = counts.path("childChunkCount").asLong();
                            boolean needed = action.equals("CHUNK") ? children == 0
                                    : counts.path("embeddedChildChunkCount").asLong() < children;
                            if (denial == null && action.equals("EMBED_MISSING") && children == 0) { denial = "CHUNKING_REQUIRED"; }
                            if (denial == null && needed && "FAILED".equals(row.get("latest_status", String.class))) {
                                String code = row.get("latest_error", String.class);
                                if (!"TRANSIENT_DEPENDENCY".equals(code)) { denial = code == null ? "UNKNOWN" : code; }
                            }
                            return new State(RunJson.hash(state.toString()), needed, denial);
                        }).one()));
    }

    public record State(String fingerprint, boolean needed, String denial) {
        public void requireEligible() {
            if (denial != null) { throw new RunException(409, "RETRY_NOT_ALLOWED", "Retry is not eligible: " + denial); }
            if (!needed) { throw new RunException(409, "RETRY_NOT_NEEDED", "This operation is already complete"); }
        }
    }
}
