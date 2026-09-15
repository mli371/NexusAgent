package com.nexusagent.query.live;

import static org.assertj.core.api.Assertions.*;

import java.time.Instant;
import java.util.List;

import com.nexusagent.common.context.RequestContext;
import com.nexusagent.context.application.ContextProperties;
import com.nexusagent.embeddings.domain.EmbeddingModelInfo;
import com.nexusagent.query.api.QueryRequest;
import com.nexusagent.query.application.QueryProperties;
import com.nexusagent.retrieval.application.RetrievalProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

class SemanticReusePolicyTest {
    final SemanticReusePolicy policy = new SemanticReusePolicy();

    @Test void cosineAndInvalidVectorsHaveExplicitBoundaries() {
        assertThat(SemanticReusePolicy.cosine(List.of(1f, 0f), List.of(2f, 0f))).isEqualTo(1);
        assertThat(SemanticReusePolicy.cosine(List.of(1f, 0f), List.of(0f, 1f))).isZero();
        assertThat(SemanticReusePolicy.cosine(List.of(1f, 0f), List.of(-1f, 0f))).isEqualTo(-1);
        for (var bad : List.of(List.of(0f, 0f), List.of(Float.NaN, 1f), List.of(Float.POSITIVE_INFINITY, 1f), List.of(1f))) {
            assertThat(SemanticReusePolicy.cosine(List.of(1f, 0f), bad)).isNaN();
        }
    }

    @Test void guardsRejectNumericTemporalNegationAndIntentConflicts() {
        assertThat(policy.features("What products were launched in 2024?"))
                .isEqualTo(policy.features("Which products were introduced in 2024?"));
        for (String different : List.of("What products were launched in 2025?", "What products were launched?",
                "What products were not launched in 2024?", "Compare products launched in 2024")) {
            assertThat(policy.features(different)).isNotEqualTo(policy.features("What products were launched in 2024?"));
        }
        assertThat(policy.features("今年发布了哪些产品？")).isNotEqualTo(policy.features("去年发布了哪些产品？"));
        assertThat(policy.features("这些发布会资料有哪些差异？")).isNotEqualTo(policy.features("这几年苹果发布会有什么特别的产品？"));
        assertThat(policy.features("发布会有哪些亮点？")).isEqualTo(policy.features("发布会有哪些特色？"));
        assertThat(policy.features("What is not forbidden?")).isEmpty();
        assertThat(policy.features("Compare the highlights")).isEmpty();
        assertThat(policy.features("hello")).isEmpty();
    }

    @Test void rankingUsesThresholdStableTiesDeduplicationAndAtMostThreeCandidates() {
        var features = policy.features("What is the policy?").orElseThrow();
        var sources = List.of(source("b", features, List.of(1f, 0f)), source("a", features, List.of(1f, 0f)),
                source("a", features, List.of(1f, 0f)), source("c", features, List.of(1f, 0f)), source("d", features, List.of(1f, 0f)),
                source("e", features, List.of(0f, 1f)));
        var ranking = policy.rank(features, List.of(1f, 0f), sources, 1);
        assertThat(ranking.matches()).extracting(match -> match.source().questionHash()).containsExactly("a", "b", "c");
        assertThat(policy.rank(features, List.of(0f, -1f), sources, 0.96).matches()).isEmpty();
        assertThat(policy.rank(policy.features("What is not allowed?").orElseThrow(), List.of(1f, 0f), sources, 0.1).matches()).isEmpty();
    }

    @Test void scopeIgnoresQuestionButSeparatesIdentityVersionsSettingsModelAndLibrary() {
        var mapper = new ObjectMapper().findAndRegisterModules();
        var settings = new RetrievalProperties();
        var versions = List.of(new LiveContextSnapshotRepository.DocumentVersion(LiveQueryServiceTest.DOC, 1));
        var model = new EmbeddingModelInfo("openai", "fixture", 3);
        var input = input("What is the policy?", LiveQueryServiceTest.OWNER, 5, 1000);
        String scope = LiveContextCacheKey.semanticScope(input, versions, model, settings, mapper);
        assertThat(RedisSemanticContextCache.key(scope)).matches("retrieval:semantic-context-v1:\\{[a-f0-9]{64}}:candidates");
        assertThat(LiveContextCacheKey.semanticScope(input("Which policy applies?", input.context(), 5, 1000), versions, model, settings, mapper)).isEqualTo(scope);
        for (var other : List.of(input("What is the policy?", new RequestContext("tenant-b", "alice"), 5, 1000),
                input("What is the policy?", new RequestContext("tenant-a", "bob"), 5, 1000),
                input("What is the policy?", input.context(), 6, 1000), input("What is the policy?", input.context(), 5, 1001),
                new LiveQueryInput("t", "s", input.question(), input.documentIds(), 5, 1000, false, input.context(), "library"))) {
            assertThat(LiveContextCacheKey.semanticScope(other, versions, model, settings, mapper)).isNotEqualTo(scope);
        }
        assertThat(LiveContextCacheKey.semanticScope(input, List.of(new LiveContextSnapshotRepository.DocumentVersion(LiveQueryServiceTest.DOC, 2)), model, settings, mapper)).isNotEqualTo(scope);
        assertThat(LiveContextCacheKey.semanticScope(input, versions, new EmbeddingModelInfo("openai", "other", 3), settings, mapper)).isNotEqualTo(scope);
        settings.setRrfK(61);
        assertThat(LiveContextCacheKey.semanticScope(input, versions, model, settings, mapper)).isNotEqualTo(scope);
    }

    @Test void rejectsInvalidConfiguration() {
        var p = new QueryProperties();
        for (double value : new double[]{0, -1, 1.01, Double.NaN, Double.POSITIVE_INFINITY}) {
            assertThatThrownBy(() -> p.setSemanticCacheMinSimilarity(value)).isInstanceOf(IllegalArgumentException.class);
        }
        for (int value : new int[]{0, 201}) {
            assertThatThrownBy(() -> p.setSemanticCacheMaxEntries(value)).isInstanceOf(IllegalArgumentException.class);
        }
    }

    static LiveQueryInput input(String question, RequestContext context, int topK, int budget) {
        return LiveQueryInput.from(new QueryRequest("s", question, List.of(LiveQueryServiceTest.DOC), topK, budget, true),
                "trace", context, new RetrievalProperties(), new ContextProperties());
    }

    private SemanticContextCache.Source source(String hash, SemanticReusePolicy.Features features, List<Float> vector) {
        return new SemanticContextCache.Source(1, "scope", hash, vector, features, "key", "fingerprint", Instant.EPOCH, Instant.MAX);
    }
}
