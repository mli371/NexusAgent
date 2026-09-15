package com.nexusagent.query.live;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexusagent.common.observation.StageObservation;
import com.nexusagent.context.application.CitationFormatter;
import com.nexusagent.context.application.ContextBuilder;
import com.nexusagent.context.domain.ContextBuildResult;
import com.nexusagent.embeddings.application.EmbeddingService;
import com.nexusagent.embeddings.domain.EmbeddingModelInfo;
import com.nexusagent.embeddings.domain.EmbeddingVector;
import com.nexusagent.query.application.QueryProperties;
import com.nexusagent.retrieval.application.RetrievalProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.data.redis.core.ReactiveValueOperations;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

class SemanticContextServiceTest {
    final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    final QueryProperties properties = new QueryProperties();
    final ContextBuilder builder = mock(ContextBuilder.class);
    final LiveQueryGuard guard = mock(LiveQueryGuard.class);
    final LiveContextSnapshotRepository snapshots = mock(LiveContextSnapshotRepository.class);
    final EmbeddingService embeddings = mock(EmbeddingService.class);
    final EmbeddingModelInfo model = new EmbeddingModelInfo("openai", "fixture", 3);
    final EmbeddingVector vector = new EmbeddingVector(List.of(1f, 0f, 0f));
    final ReactiveStringRedisTemplate redis = mock(ReactiveStringRedisTemplate.class);
    @SuppressWarnings("unchecked") final ReactiveValueOperations<String, String> values = mock(ReactiveValueOperations.class);
    final Map<String, String> stored = new HashMap<>();
    final List<SemanticContextCache.Source> sources = new ArrayList<>();
    final SemanticContextCache semantic = mock(SemanticContextCache.class);
    final List<LiveContextSnapshotRepository.DocumentVersion> versions = List.of(new LiveContextSnapshotRepository.DocumentVersion(LiveQueryServiceTest.DOC, 1));
    ContextBuildResult evidence;
    LiveContextService service;
    QueryTrace lastTrace;

    @BeforeEach void setup() {
        var original = LiveQueryServiceTest.evidence();
        evidence = new ContextBuildResult(original.query(), original.rerankedCandidates(), original.selectedChildChunks(),
                original.expandedParentContexts(), original.citations(), new CitationFormatter().formatContext(original.expandedParentContexts(), original.citations()), original.debugMetadata());
        when(redis.opsForValue()).thenReturn(values);
        when(values.get(anyString())).thenAnswer(call -> Mono.defer(() -> Mono.justOrEmpty(stored.get(call.getArgument(0)))));
        when(values.set(anyString(), anyString(), any(Duration.class))).thenAnswer(call -> Mono.fromSupplier(() -> {
            stored.put(call.getArgument(0), call.getArgument(1)); return true;
        }));
        when(semantic.enabled()).thenReturn(true);
        when(semantic.read(anyString(), anyInt())).thenAnswer(call -> Mono.just(new SemanticContextCache.Read(sources.stream()
                .filter(s -> s.scopeHash().equals(call.getArgument(0))).toList(), "found")));
        when(semantic.put(any())).thenAnswer(call -> Mono.fromRunnable(() -> sources.add(call.getArgument(0))));
        when(embeddings.embed(anyString())).thenReturn(Mono.just(vector));
        when(builder.build(anyString(), anyList(), anyInt(), anyInt(), any())).thenReturn(Mono.just(evidence));
        when(builder.buildWithEmbedding(anyString(), anyList(), anyInt(), anyInt(), any(), any())).thenReturn(Mono.just(evidence));
        when(guard.access(any())).thenReturn(Mono.empty()); when(guard.evidence(any(), any())).thenReturn(Mono.empty());
        when(snapshots.read(any())).thenReturn(Mono.just(versions)); when(snapshots.assertCurrent(any(), anyList())).thenReturn(Mono.empty());
        service = new LiveContextService(builder, guard, snapshots, new RedisLiveContextCache(redis, mapper, properties), model,
                new RetrievalProperties(), mapper, embeddings, semantic, properties);
    }

    @Test void exactThenSemanticReuseDoesNotPromoteAliasesOrReseedAndShowsActualEmbedding() {
        assertThat(load("What is the policy?").block().cacheStatus()).isEqualTo("miss");
        var originalExpiry = sources.get(0).expiresAt();
        assertThat(load("What is the policy?").block().cacheStatus()).isEqualTo("hit");
        verify(embeddings, times(1)).embed(anyString());
        assertThat(load("Which policy applies?").block().cacheStatus()).isEqualTo("semantic_hit");
        assertThat(load("Which policy applies?").block().context().query()).isEqualTo("Which policy applies?");
        assertThat(stored).hasSize(1); assertThat(sources).hasSize(1);
        assertThat(sources.get(0).expiresAt()).isEqualTo(originalExpiry);
        verify(builder, times(1)).buildWithEmbedding(anyString(), anyList(), anyInt(), anyInt(), any(), eq(vector));
        verify(embeddings, times(3)).embed(anyString());
        assertThat(lastTrace.history()).anySatisfy(stage -> {
            assertThat(stage.stage()).isEqualTo("query_embedding"); assertThat(stage.status()).isEqualTo("succeeded");
        }).anySatisfy(stage -> {
            assertThat(stage.stage()).isEqualTo("semantic_cache_lookup"); assertThat(stage.summary()).containsEntry("similarity", 1.0);
        }).anySatisfy(stage -> {
            assertThat(stage.stage()).isEqualTo("vector_search"); assertThat(stage.status()).isEqualTo("skipped");
        }).anySatisfy(stage -> {
            assertThat(stage.stage()).isEqualTo("child_selection");
            assertThat(stage.status()).isEqualTo("skipped");
            assertThat(stage.summary()).containsEntry("reason", "cache_reuse");
        });
    }

    @Test void semanticMissPassesSameVectorToBuilderAndDoesNotRepeatEmbedding() {
        load("What is the policy?").block();
        var other = new EmbeddingVector(List.of(0f, 1f, 0f));
        when(embeddings.embed("Which rule applies?")).thenReturn(Mono.just(other));
        assertThat(load("Which rule applies?").block().cacheStatus()).isEqualTo("miss");
        verify(embeddings).embed("Which rule applies?");
        verify(builder).buildWithEmbedding(eq("Which rule applies?"), anyList(), anyInt(), anyInt(), any(), same(other));
    }

    @Test void onlySuccessfulNonemptyExactWritesCanCreateSources() {
        when(values.set(anyString(), anyString(), any(Duration.class))).thenReturn(Mono.just(false));
        assertThat(load("What is the policy?").block().cacheStatus()).isEqualTo("miss");
        assertThat(sources).isEmpty();
        when(builder.buildWithEmbedding(anyString(), anyList(), anyInt(), anyInt(), any(), any())).thenReturn(Mono.just(LiveQueryServiceTest.emptyEvidence()));
        assertThat(load("Which rule applies?").block().context().citations()).isEmpty();
        assertThat(sources).isEmpty();
    }

    @Test void danglingOrReplacedEvidenceDoesNotBecomeASemanticHit() throws Exception {
        load("What is the policy?").block();
        var key = sources.get(0).cacheKey();
        var json = mapper.readTree(stored.get(key));
        ((com.fasterxml.jackson.databind.node.ObjectNode) json.path("context")).put("finalContextText", "changed");
        stored.put(key, mapper.writeValueAsString(json));
        assertThat(load("Which rule applies?").block().cacheStatus()).isEqualTo("miss");
        stored.clear();
        assertThat(load("What rule should apply?").block().cacheStatus()).isEqualTo("miss");
    }

    @Test void permissionAndVersionFailuresAreNotSwallowedAsCacheMisses() {
        load("What is the policy?").block(); clearInvocations(builder);
        when(guard.evidence(any(), any())).thenReturn(Mono.error(new com.nexusagent.common.error.OperationException(
                org.springframework.http.HttpStatus.NOT_FOUND, "DOCUMENT_NOT_ACCESSIBLE", "Denied")));
        StepVerifier.create(load("Which rule applies?")).expectError(com.nexusagent.common.error.OperationException.class).verify();
        verifyNoInteractions(builder);
        when(guard.evidence(any(), any())).thenReturn(Mono.empty());
        when(snapshots.assertCurrent(any(), anyList())).thenReturn(Mono.error(LiveQueryGuard.changed()));
        StepVerifier.create(load("Which rule applies?")).expectError(com.nexusagent.common.error.OperationException.class).verify();
        assertThat(sources).hasSize(1);
    }

    @Test void disabledOrIneligibleQueriesKeepExistingExactCachePath() {
        when(semantic.enabled()).thenReturn(false);
        assertThat(load("What is the policy?").block().cacheStatus()).isEqualTo("miss");
        assertThat(load("What is the policy?").block().cacheStatus()).isEqualTo("hit");
        when(semantic.enabled()).thenReturn(true);
        assertThat(load("hello").block().cacheStatus()).isEqualTo("miss");
        verifyNoInteractions(embeddings);
        verify(semantic, never()).read(anyString(), anyInt());
    }

    private Mono<LiveContextService.Result> load(String question) {
        var input = SemanticReusePolicyTest.input(question, LiveQueryServiceTest.OWNER, 5, 1000);
        lastTrace = new QueryTrace("trace", true, mapper);
        return service.load(input, lastTrace).contextWrite(context -> context.put(StageObservation.KEY, lastTrace));
    }
}
