package com.nexusagent.query.redis;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

import com.nexusagent.context.application.ContextProperties;
import com.nexusagent.query.application.QueryCacheKey;
import com.nexusagent.retrieval.application.RetrievalProperties;
import org.junit.jupiter.api.Test;

class RedisKeyFactoryTest {

    private final RedisKeyFactory keyFactory = new RedisKeyFactory();

    @Test
    void buildsExpectedRedisKeyFormats() {
        assertThat(keyFactory.sessionRecent("s1")).isEqualTo("session:s1:recent");
        assertThat(keyFactory.sessionSummary("s1")).isEqualTo("session:s1:summary");
        assertThat(keyFactory.toolResult("s1", "context")).isEqualTo("tool:s1:context:result");
        assertThat(keyFactory.queryStatus("trace-1")).isEqualTo("query:trace-1:status");
    }

    @Test
    void retrievalKeyUsesStableNormalizedHash() {
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        RetrievalProperties retrievalProperties = new RetrievalProperties();
        ContextProperties contextProperties = new ContextProperties();

        QueryCacheKey left = new QueryCacheKey("  Security   Policy ", List.of(first, second), 5, 1000);
        QueryCacheKey right = new QueryCacheKey("security policy", List.of(second, first), 5, 1000);

        String leftKey = keyFactory.retrievalCandidates(left, retrievalProperties, contextProperties);
        String rightKey = keyFactory.retrievalCandidates(right, retrievalProperties, contextProperties);

        assertThat(leftKey).isEqualTo(rightKey);
        assertThat(leftKey).startsWith("retrieval:");
        assertThat(leftKey).endsWith(":candidates");
        assertThat(leftKey.substring("retrieval:".length(), leftKey.length() - ":candidates".length()))
                .matches("[a-f0-9]{64}");
    }

    @Test
    void retrievalKeyChangesWhenSettingsThatAffectOutputChange() {
        UUID documentId = UUID.randomUUID();
        RetrievalProperties retrievalProperties = new RetrievalProperties();
        ContextProperties contextProperties = new ContextProperties();
        QueryCacheKey base = new QueryCacheKey("security policy", List.of(documentId), 5, 1000);

        String baseKey = keyFactory.retrievalCandidates(base, retrievalProperties, contextProperties);
        String changedDocumentIds = keyFactory.retrievalCandidates(
                new QueryCacheKey("security policy", List.of(UUID.randomUUID()), 5, 1000),
                retrievalProperties,
                contextProperties
        );
        String changedTopK = keyFactory.retrievalCandidates(
                new QueryCacheKey("security policy", List.of(documentId), 6, 1000),
                retrievalProperties,
                contextProperties
        );
        String changedBudget = keyFactory.retrievalCandidates(
                new QueryCacheKey("security policy", List.of(documentId), 5, 1200),
                retrievalProperties,
                contextProperties
        );
        String changedTenant = keyFactory.retrievalCandidates(
                new QueryCacheKey("tenant-a", "actor-1", "security policy", List.of(documentId), 5, 1000),
                retrievalProperties,
                contextProperties
        );
        String changedActor = keyFactory.retrievalCandidates(
                new QueryCacheKey("default", "actor-2", "security policy", List.of(documentId), 5, 1000),
                retrievalProperties,
                contextProperties
        );

        assertThat(changedDocumentIds).isNotEqualTo(baseKey);
        assertThat(changedTopK).isNotEqualTo(baseKey);
        assertThat(changedBudget).isNotEqualTo(baseKey);
        assertThat(changedTenant).isNotEqualTo(baseKey);
        assertThat(changedActor).isNotEqualTo(baseKey);
    }

    @Test
    void retrievalKeyUsesTenantAndActorAsAccessScope() {
        UUID documentId = UUID.randomUUID();
        RetrievalProperties retrievalProperties = new RetrievalProperties();
        ContextProperties contextProperties = new ContextProperties();

        QueryCacheKey tenantAActor1 = new QueryCacheKey(
                "tenant-a",
                "actor-1",
                "security policy",
                List.of(documentId),
                5,
                1000
        );
        QueryCacheKey tenantBActor1 = new QueryCacheKey(
                "tenant-b",
                "actor-1",
                "security policy",
                List.of(documentId),
                5,
                1000
        );
        QueryCacheKey tenantAActor2 = new QueryCacheKey(
                "tenant-a",
                "actor-2",
                "security policy",
                List.of(documentId),
                5,
                1000
        );

        String tenantAActor1Key = keyFactory.retrievalCandidates(tenantAActor1, retrievalProperties, contextProperties);
        String tenantBActor1Key = keyFactory.retrievalCandidates(tenantBActor1, retrievalProperties, contextProperties);
        String tenantAActor2Key = keyFactory.retrievalCandidates(tenantAActor2, retrievalProperties, contextProperties);

        assertThat(tenantBActor1Key).isNotEqualTo(tenantAActor1Key);
        assertThat(tenantAActor2Key).isNotEqualTo(tenantAActor1Key);
    }

    @Test
    void redisTtlDefaultsAreDifferentByDataType() {
        NexusRedisProperties properties = new NexusRedisProperties();

        assertThat(properties.getRecentSessionTtl()).isEqualTo(Duration.ofHours(24));
        assertThat(properties.getSessionSummaryTtl()).isEqualTo(Duration.ofDays(7));
        assertThat(properties.getRetrievalCacheTtl()).isEqualTo(Duration.ofMinutes(30));
        assertThat(properties.getToolOutputTtl()).isEqualTo(Duration.ofHours(2));
        assertThat(properties.getQueryStatusTtl()).isEqualTo(Duration.ofMinutes(30));
    }
}
