package com.nexusagent.query.live;

import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Comparator;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexusagent.embeddings.domain.EmbeddingModelInfo;
import com.nexusagent.retrieval.application.RetrievalProperties;

final class LiveContextCacheKey {
    // Bump when SQL ranking, heuristic weights, context trimming or citation formatting changes.
    private static final String PIPELINE_VERSION = "hybrid-english-rrf-heuristic-parent-v1";

    static String create(LiveQueryInput input, List<LiveContextSnapshotRepository.DocumentVersion> versions,
                         EmbeddingModelInfo model, RetrievalProperties settings, ObjectMapper mapper) {
        try {
            var descriptor = new Descriptor(1, PIPELINE_VERSION, input.context().tenantId(), input.context().actorId(),
                    input.question(), input.scope(), versions.stream().sorted(Comparator.comparing(v -> v.documentId().toString())).toList(),
                    input.topK(), input.budget(), model, settings.getRrfK());
            return "retrieval:live-context-v1:" + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(mapper.writeValueAsBytes(descriptor))) + ":context";
        } catch (Exception error) {
            throw new IllegalStateException("Unable to create live context cache key");
        }
    }

    private record Descriptor(int schemaVersion, String pipelineVersion, String tenantId, String actorId, String question,
                              String scope, List<LiveContextSnapshotRepository.DocumentVersion> documents,
                              int topK, int budgetChars, EmbeddingModelInfo embedding, int rrfK) { }
}
