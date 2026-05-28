package com.nexusagent.query.redis;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Comparator;
import java.util.Locale;
import java.util.UUID;
import java.util.stream.Collectors;

import com.nexusagent.context.application.ContextProperties;
import com.nexusagent.query.application.QueryCacheKey;
import com.nexusagent.retrieval.application.RetrievalProperties;

public class RedisKeyFactory {

    private static final String CACHE_SCHEMA = "context-cache-v1";

    public String sessionRecent(String sessionId) {
        return "session:%s:recent".formatted(sessionId);
    }

    public String sessionSummary(String sessionId) {
        return "session:%s:summary".formatted(sessionId);
    }

    public String retrievalCandidates(
            QueryCacheKey key,
            RetrievalProperties retrievalProperties,
            ContextProperties contextProperties
    ) {
        return "retrieval:%s:candidates".formatted(queryHash(key, retrievalProperties, contextProperties));
    }

    public String toolResult(String sessionOrTraceId, String toolCallId) {
        return "tool:%s:%s:result".formatted(sessionOrTraceId, toolCallId);
    }

    public String queryStatus(String traceId) {
        return "query:%s:status".formatted(traceId);
    }

    public String queryHash(
            QueryCacheKey key,
            RetrievalProperties retrievalProperties,
            ContextProperties contextProperties
    ) {
        String normalizedQuestion = normalizeQuestion(key.question());
        String documentIds = key.documentIds().stream()
                .sorted(Comparator.comparing(UUID::toString))
                .map(UUID::toString)
                .collect(Collectors.joining(","));
        int effectiveTopK = key.topK() == null
                ? retrievalProperties.getDefaultTopK()
                : Math.min(key.topK(), retrievalProperties.getMaxTopK());
        int effectiveContextBudget = key.contextBudgetChars() == null
                ? contextProperties.getDefaultBudgetChars()
                : Math.min(key.contextBudgetChars(), contextProperties.getMaxBudgetChars());
        String canonical = """
                schema=%s
                tenantId=%s
                actorId=%s
                question=%s
                documentIds=%s
                topK=%d
                contextBudgetChars=%d
                retrievalDefaultTopK=%d
                retrievalMaxTopK=%d
                rrfK=%d
                contextDefaultBudgetChars=%d
                contextMaxBudgetChars=%d
                """.formatted(
                CACHE_SCHEMA,
                key.tenantId(),
                key.actorId(),
                normalizedQuestion,
                documentIds,
                effectiveTopK,
                effectiveContextBudget,
                retrievalProperties.getDefaultTopK(),
                retrievalProperties.getMaxTopK(),
                retrievalProperties.getRrfK(),
                contextProperties.getDefaultBudgetChars(),
                contextProperties.getMaxBudgetChars()
        );
        return sha256(canonical);
    }

    private String normalizeQuestion(String question) {
        if (question == null) {
            return "";
        }
        return question.trim().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
    }

    private String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder builder = new StringBuilder(bytes.length * 2);
            for (byte b : bytes) {
                builder.append(String.format("%02x", b));
            }
            return builder.toString();
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is not available", exception);
        }
    }
}
