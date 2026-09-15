package com.nexusagent.query.live;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexusagent.common.context.RequestContext;
import com.nexusagent.context.application.CitationFormatter;
import com.nexusagent.context.application.ContextBuilder;
import com.nexusagent.context.application.ContextProperties;
import com.nexusagent.context.domain.ContextBuildResult;
import com.nexusagent.embeddings.domain.EmbeddingModelInfo;
import com.nexusagent.query.api.QueryRequest;
import com.nexusagent.query.application.QueryProperties;
import com.nexusagent.retrieval.application.RetrievalProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.data.redis.core.ReactiveValueOperations;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

class LiveContextCacheTest {
    final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    final QueryProperties properties = new QueryProperties();
    final ReactiveStringRedisTemplate redis = mock(ReactiveStringRedisTemplate.class);
    @SuppressWarnings("unchecked")
    final ReactiveValueOperations<String, String> values = mock(ReactiveValueOperations.class);
    final RedisLiveContextCache cache = new RedisLiveContextCache(redis, mapper, properties);
    final ContextBuilder builder = mock(ContextBuilder.class);
    final LiveQueryGuard guard = mock(LiveQueryGuard.class);
    final LiveContextSnapshotRepository snapshots = mock(LiveContextSnapshotRepository.class);
    final EmbeddingModelInfo model = new EmbeddingModelInfo("openai", "test-model", 384);
    final RetrievalProperties retrieval = new RetrievalProperties();
    final LiveQueryInput input = LiveQueryInput.from(LiveQueryServiceTest.request(true), "trace", LiveQueryServiceTest.OWNER,
            retrieval, new ContextProperties());
    final List<LiveContextSnapshotRepository.DocumentVersion> versions = List.of(
            new LiveContextSnapshotRepository.DocumentVersion(LiveQueryServiceTest.DOC, 3));
    ContextBuildResult evidence;

    @BeforeEach
    void setup() {
        var original = LiveQueryServiceTest.evidence();
        evidence = new ContextBuildResult(original.query(), original.rerankedCandidates(), original.selectedChildChunks(),
                original.expandedParentContexts(), original.citations(), new CitationFormatter().formatContext(
                original.expandedParentContexts(), original.citations()), original.debugMetadata());
        when(redis.opsForValue()).thenReturn(values);
        when(values.get(anyString())).thenReturn(Mono.empty());
        when(values.set(anyString(), anyString(), any(Duration.class))).thenReturn(Mono.just(true));
        when(builder.build(anyString(), anyList(), anyInt(), anyInt(), any())).thenReturn(Mono.just(evidence));
        when(guard.access(any())).thenReturn(Mono.empty());
        when(guard.evidence(any(), any())).thenReturn(Mono.empty());
        when(snapshots.read(any())).thenReturn(Mono.just(versions));
        when(snapshots.assertCurrent(any(), anyList())).thenReturn(Mono.empty());
    }

    @Test
    void keysSeparateIdentityVersionsScopeModelsAndSettingsButNotTraceOrDebug() {
        String base = key(input, versions, model);
        assertThat(base).matches("retrieval:live-context-v1:[a-f0-9]{64}:context").doesNotContain("alice", "policy");
        for (var identity : List.of(RequestContext.fromHeaders("tenant-b", "alice"), RequestContext.fromHeaders("tenant-a", "bob"))) {
            assertThat(key(LiveQueryInput.from(LiveQueryServiceTest.request(true), "trace", identity, retrieval, new ContextProperties()), versions, model)).isNotEqualTo(base);
        }
        assertThat(key(input, List.of(new LiveContextSnapshotRepository.DocumentVersion(LiveQueryServiceTest.DOC, 4)), model)).isNotEqualTo(base);
        assertThat(key(input, List.of(new LiveContextSnapshotRepository.DocumentVersion(UUID.randomUUID(), 3)), model)).isNotEqualTo(base);
        assertThat(key(input, versions, new EmbeddingModelInfo("openai", "other", 384))).isNotEqualTo(base);
        for (var request : List.of(new QueryRequest("s", "policy", input.documentIds(), 6, 1000, true),
                new QueryRequest("s", "policy", input.documentIds(), 5, 1001, true),
                new QueryRequest("s", "Policy", input.documentIds(), 5, 1000, true))) {
            assertThat(key(LiveQueryInput.from(request, "trace", input.context(), retrieval, new ContextProperties()), versions, model)).isNotEqualTo(base);
        }
        var library = new LiveQueryInput("other-trace", "other-session", input.question(), input.documentIds(), 5, 1000, true, input.context(), "library");
        assertThat(key(library, versions, model)).isNotEqualTo(base);
        assertThat(key(LiveQueryInput.from(LiveQueryServiceTest.request(false), "other", input.context(), retrieval, new ContextProperties()), versions, model)).isEqualTo(base);
        retrieval.setRrfK(61);
        assertThat(key(input, versions, model)).isNotEqualTo(base);
    }

    @Test
    void writesVersionedJsonWithTtlAndNoQuestionOrAnswerFields() throws Exception {
        cache.put("key", evidence).block();
        var json = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(values).set(eq("key"), json.capture(), eq(Duration.ofMinutes(15)));
        var value = mapper.readTree(json.getValue());
        assertThat(value.path("schemaVersion").asInt()).isEqualTo(2);
        assertThat(value.at("/context/query").asText()).isEmpty();
        assertThat(value.path("context").has("answer")).isFalse();
        when(values.get("key")).thenReturn(Mono.just(json.getValue()));
        assertThat(cache.get("key").block().context().finalContextText()).isEqualTo(evidence.finalContextText());
    }

    @Test
    void rejectsMalformedWrongKeyExpiredAndOversizedEntries() throws Exception {
        for (String json : List.of("private-corrupt-body", "x".repeat(131073),
                mapper.writeValueAsString(new RedisLiveContextCache.Envelope(1, "key", Instant.now(), evidence)),
                mapper.writeValueAsString(new RedisLiveContextCache.Envelope(2, "wrong-key", Instant.now(), evidence)),
                mapper.writeValueAsString(new RedisLiveContextCache.Envelope(2, "key", Instant.now().minusSeconds(1000), evidence)),
                mapper.writeValueAsString(new RedisLiveContextCache.Envelope(99, "key", Instant.now(), evidence)))) {
            when(values.get("key")).thenReturn(Mono.just(json));
            assertThat(cache.get("key").block().context()).isNull();
        }
    }

    @Test
    void skipsOversizedAndEmptyWritesAndIgnoresRedisWriteFailure() {
        properties.setLiveCacheMaxBytes(50);
        cache.put("key", evidence).block();
        cache.put("key", LiveQueryServiceTest.emptyEvidence()).block();
        verify(values, never()).set(anyString(), anyString(), any(Duration.class));
        properties.setLiveCacheMaxBytes(131072);
        when(values.set(anyString(), anyString(), any(Duration.class))).thenReturn(Mono.error(new IllegalStateException("private")));
        StepVerifier.create(cache.put("key", evidence)).verifyComplete();
    }

    @Test
    void unavailableOrSlowRedisBecomesMissAndFreshContextStillWorks() {
        when(values.get(anyString())).thenReturn(Mono.error(new IllegalStateException("private")));
        assertThat(load().block().cacheStatus()).isEqualTo("miss");
        verify(builder).build(anyString(), anyList(), anyInt(), anyInt(), any());
        when(values.get(anyString())).thenReturn(Mono.never());
        StepVerifier.withVirtualTime(this::load).thenAwait(Duration.ofSeconds(1))
                .assertNext(result -> assertThat(result.cacheStatus()).isEqualTo("miss")).verifyComplete();
    }

    @Test
    void validHitSkipsBuilderAndChecksEvidenceAndVersion() throws Exception {
        String key = key(input, versions, model);
        when(values.get(key)).thenReturn(Mono.just(mapper.writeValueAsString(new RedisLiveContextCache.Envelope(2, key, Instant.now(), evidence))));
        assertThat(load().block().cacheStatus()).isEqualTo("hit");
        verifyNoInteractions(builder);
        verify(guard).evidence(any(), any());
        verify(snapshots).assertCurrent(input, versions);
    }

    @Test
    void invalidContextTextIsRebuiltAndPermissionDenialNeverFallsBack() throws Exception {
        String key = key(input, versions, model);
        var tampered = new ContextBuildResult(evidence.query(), evidence.rerankedCandidates(), evidence.selectedChildChunks(),
                evidence.expandedParentContexts(), evidence.citations(), "unrelated evidence", evidence.debugMetadata());
        when(values.get(key)).thenReturn(Mono.just(mapper.writeValueAsString(new RedisLiveContextCache.Envelope(2, key, Instant.now(), tampered))));
        assertThat(load().block().cacheStatus()).isEqualTo("miss");
        clearInvocations(builder);
        when(values.get(key)).thenReturn(Mono.just(mapper.writeValueAsString(new RedisLiveContextCache.Envelope(2, key, Instant.now(), evidence))));
        when(guard.evidence(any(), any())).thenReturn(Mono.error(new com.nexusagent.common.error.OperationException(
                org.springframework.http.HttpStatus.NOT_FOUND, "DOCUMENT_NOT_ACCESSIBLE", "Denied")));
        StepVerifier.create(load()).expectError(com.nexusagent.common.error.OperationException.class).verify();
        verifyNoInteractions(builder);
    }

    @Test
    void versionChangeDuringBuildIsNotCached() {
        when(snapshots.assertCurrent(any(), anyList())).thenReturn(Mono.error(LiveQueryGuard.changed()));
        StepVerifier.create(load()).expectError(com.nexusagent.common.error.OperationException.class).verify();
        verify(values, never()).set(anyString(), anyString(), any(Duration.class));
    }

    @Test
    void validatesCacheLimits() {
        assertThatThrownBy(() -> properties.setLiveCacheTtl(Duration.ZERO)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> properties.setLiveCacheTtl(Duration.ofHours(2))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> properties.setLiveCacheTimeout(Duration.ofSeconds(3))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> properties.setLiveCacheMaxBytes(0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> properties.setLiveCacheMaxBytes(1048577)).isInstanceOf(IllegalArgumentException.class);
    }

    private Mono<LiveContextService.Result> load() {
        return new LiveContextService(builder, guard, snapshots, cache, model, retrieval, mapper)
                .load(input, new QueryTrace(input.traceId(), true, mapper));
    }
    private String key(LiveQueryInput value, List<LiveContextSnapshotRepository.DocumentVersion> docs, EmbeddingModelInfo info) {
        return LiveContextCacheKey.create(value, docs, info, retrieval, mapper);
    }
}
