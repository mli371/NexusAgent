package com.nexusagent.query.live;

import java.security.MessageDigest;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import java.util.List;
import java.util.Comparator;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexusagent.embeddings.domain.EmbeddingModelInfo;
import com.nexusagent.retrieval.application.RetrievalProperties;

final class LiveContextCacheKey {
    // Bump when SQL ranking, heuristic weights, context trimming or citation formatting changes.
    private static final String PIPELINE_VERSION = "hybrid-english-rrf-heuristic-" + com.nexusagent.context.application.ContextBudgetAllocator.VERSION;

    static String create(LiveQueryInput input, List<LiveContextSnapshotRepository.DocumentVersion> versions,
                         EmbeddingModelInfo model, RetrievalProperties settings, ObjectMapper mapper) {
        try {
            return "retrieval:live-context-v1:" + hash(mapper.writeValueAsBytes(descriptor(input, versions, model, settings, input.question()))) + ":context";
        } catch (Exception error) {
            throw new IllegalStateException("Unable to create live context cache key");
        }
    }

    static String semanticScope(LiveQueryInput input, List<LiveContextSnapshotRepository.DocumentVersion> versions,
                                EmbeddingModelInfo model, RetrievalProperties settings, ObjectMapper mapper) {
        try {
            return hash(mapper.writeValueAsBytes(new SemanticDescriptor(SemanticReusePolicy.VERSION,
                    descriptor(input, versions, model, settings, ""))));
        } catch (Exception error) {
            throw new IllegalStateException("Unable to create semantic cache scope");
        }
    }

    private static Descriptor descriptor(LiveQueryInput input, List<LiveContextSnapshotRepository.DocumentVersion> versions,
                                         EmbeddingModelInfo model, RetrievalProperties settings, String question) {
        return new Descriptor(1, PIPELINE_VERSION, input.context().tenantId(), input.context().actorId(), question,
                input.scope(), versions.stream().sorted(Comparator.comparing(v -> v.documentId().toString())).toList(),
                input.topK(), input.budget(), model, settings.getRrfK());
    }

    static String hash(String value) { return hash(value.getBytes(StandardCharsets.UTF_8)); }

    static String hash(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }

    private record SemanticDescriptor(String policyVersion, Descriptor scope) { }

    private record Descriptor(int schemaVersion, String pipelineVersion, String tenantId, String actorId, String question,
                              String scope, List<LiveContextSnapshotRepository.DocumentVersion> documents,
                              int topK, int budgetChars, EmbeddingModelInfo embedding, int rrfK) { }
}
