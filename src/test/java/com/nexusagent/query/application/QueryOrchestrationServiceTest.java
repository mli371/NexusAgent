package com.nexusagent.query.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.lenient;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import com.nexusagent.common.context.RequestContext;
import com.nexusagent.common.error.BadRequestException;
import com.nexusagent.context.application.ContextBuilder;
import com.nexusagent.context.domain.Citation;
import com.nexusagent.context.domain.ContextBuildResult;
import com.nexusagent.context.domain.ContextDebugMetadata;
import com.nexusagent.query.api.QueryRequest;
import com.nexusagent.query.api.QueryResponse;
import com.nexusagent.retrieval.domain.HybridRetrievalResult;
import com.nexusagent.enterprise.audit.AuditEventType;
import com.nexusagent.enterprise.audit.AuditService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

@ExtendWith(MockitoExtension.class)
class QueryOrchestrationServiceTest {

    @Mock
    ContextBuilder contextBuilder;

    @Mock
    AnswerGenerator answerGenerator;

    @Mock
    AuditService auditService;

    @BeforeEach
    void setUp() {
        lenient().when(auditService.record(any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(Mono.empty());
        lenient().when(auditService.hashSensitiveValue(any())).thenReturn("question-hash");
    }

    @Test
    void debugFalseHidesInternalContextAndDebugFields() {
        QueryOrchestrationService service = service(answerGenerator, Duration.ofSeconds(5));
        ContextBuildResult context = context("security policy", retrievalResult("security policy"));

        when(contextBuilder.build("security policy", List.of(), 5, 1000, RequestContext.defaults())).thenReturn(Mono.just(context));
        when(answerGenerator.generate("security policy", context)).thenReturn(Mono.just(generatedAnswer()));
        StepVerifier.create(service.execute(new QueryRequest("s1", "security policy", List.of(), 5, 1000, false)))
                .assertNext(response -> {
                    assertThat(response.traceId()).isNotBlank();
                    assertThat(response.answer()).contains("placeholder");
                    assertThat(response.citations()).hasSize(1);
                    assertThat(response.finalContextText()).isNull();
                    assertThat(response.retrievalDebug()).isNull();
                    assertThat(response.contextDebug()).isNull();
                    assertThat(response.limitations()).isNull();
                    assertThat(response.retrievalCacheStatus()).isNull();
                })
                .verifyComplete();
    }

    @Test
    void debugTrueReturnsContextAndRetrievalDebugFields() {
        QueryOrchestrationService service = service(answerGenerator, Duration.ofSeconds(5));
        ContextBuildResult context = context("security policy", retrievalResult("security policy"));

        when(contextBuilder.build("security policy", List.of(), 5, 1000, RequestContext.defaults())).thenReturn(Mono.just(context));
        when(answerGenerator.generate("security policy", context)).thenReturn(Mono.just(generatedAnswer()));
        StepVerifier.create(service.execute(new QueryRequest("s1", "security policy", List.of(), 5, 1000, true)))
                .assertNext(response -> {
                    assertThat(response.finalContextText()).contains("Security policy");
                    assertThat(response.retrievalDebug()).isNotNull();
                    assertThat(response.contextDebug()).isNotNull();
                    assertThat(response.limitations()).isNotEmpty();
                    assertThat(response.retrievalCacheStatus()).isEqualTo("miss");
                })
                .verifyComplete();
    }

    @Test
    void repeatedIdenticalQueryReportsMissThenHit() {
        MapRetrievalCacheService cache = new MapRetrievalCacheService();
        QueryOrchestrationService service = service(
                new LocalTemplateAnswerGenerator(),
                Duration.ofSeconds(5),
                cache,
                new NoOpToolOutputStore()
        );
        ContextBuildResult context = context("security policy", retrievalResult("security policy"));

        when(contextBuilder.build("security policy", List.of(), 5, 1000, RequestContext.defaults())).thenReturn(Mono.just(context));

        QueryRequest request = new QueryRequest("s1", "security policy", List.of(), 5, 1000, true);
        StepVerifier.create(service.execute(request).concatWith(service.execute(request)).collectList())
                .assertNext(responses -> {
                    assertThat(responses).hasSize(2);
                    assertThat(responses.get(0).retrievalCacheStatus()).isEqualTo("miss");
                    assertThat(responses.get(1).retrievalCacheStatus()).isEqualTo("hit");
                })
                .verifyComplete();

        verify(contextBuilder, times(1)).build("security policy", List.of(), 5, 1000, RequestContext.defaults());
    }

    @Test
    void retrievalCacheIsScopedByTenantAndActor() {
        MapRetrievalCacheService cache = new MapRetrievalCacheService();
        QueryOrchestrationService service = service(
                new LocalTemplateAnswerGenerator(),
                Duration.ofSeconds(5),
                cache,
                new NoOpToolOutputStore()
        );
        RequestContext tenantA = new RequestContext("tenant-a", "actor-1");
        RequestContext tenantB = new RequestContext("tenant-b", "actor-1");
        ContextBuildResult tenantAContext = context("security policy", retrievalResult("security policy"));
        ContextBuildResult tenantBContext = context("security policy", retrievalResult("security policy"));
        QueryRequest request = new QueryRequest("s1", "security policy", List.of(), 5, 1000, true);

        when(contextBuilder.build("security policy", List.of(), 5, 1000, tenantA)).thenReturn(Mono.just(tenantAContext));
        when(contextBuilder.build("security policy", List.of(), 5, 1000, tenantB)).thenReturn(Mono.just(tenantBContext));

        StepVerifier.create(service.execute(request, "trace-a-1", tenantA)
                        .concatWith(service.execute(request, "trace-b-1", tenantB))
                        .concatWith(service.execute(request, "trace-a-2", tenantA))
                        .collectList())
                .assertNext(responses -> {
                    assertThat(responses).hasSize(3);
                    assertThat(responses.get(0).retrievalCacheStatus()).isEqualTo("miss");
                    assertThat(responses.get(1).retrievalCacheStatus()).isEqualTo("miss");
                    assertThat(responses.get(2).retrievalCacheStatus()).isEqualTo("hit");
                })
                .verifyComplete();

        assertThat(cache.keys()).contains(
                new QueryCacheKey("tenant-a", "actor-1", "security policy", List.of(), 5, 1000),
                new QueryCacheKey("tenant-b", "actor-1", "security policy", List.of(), 5, 1000)
        );
        verify(contextBuilder, times(1)).build("security policy", List.of(), 5, 1000, tenantA);
        verify(contextBuilder, times(1)).build("security policy", List.of(), 5, 1000, tenantB);
    }

    @Test
    void retrievalCacheSeparatesPrivateVisibilityAccessByActor() {
        MapRetrievalCacheService cache = new MapRetrievalCacheService();
        QueryOrchestrationService service = service(
                new LocalTemplateAnswerGenerator(),
                Duration.ofSeconds(5),
                cache,
                new NoOpToolOutputStore()
        );
        RequestContext owner = new RequestContext("tenant-a", "owner-1");
        RequestContext otherActor = new RequestContext("tenant-a", "actor-2");
        ContextBuildResult ownerContext = context("private policy", retrievalResult("private policy"));
        ContextBuildResult otherActorContext = context("private policy", retrievalResult("private policy"));
        QueryRequest request = new QueryRequest("s1", "private policy", List.of(), 5, 1000, true);

        when(contextBuilder.build("private policy", List.of(), 5, 1000, owner)).thenReturn(Mono.just(ownerContext));
        when(contextBuilder.build("private policy", List.of(), 5, 1000, otherActor)).thenReturn(Mono.just(otherActorContext));

        StepVerifier.create(service.execute(request, "trace-owner", owner)
                        .concatWith(service.execute(request, "trace-other", otherActor))
                        .collectList())
                .assertNext(responses -> {
                    assertThat(responses).hasSize(2);
                    assertThat(responses.get(0).retrievalCacheStatus()).isEqualTo("miss");
                    assertThat(responses.get(1).retrievalCacheStatus()).isEqualTo("miss");
                })
                .verifyComplete();

        assertThat(cache.keys()).contains(
                new QueryCacheKey("tenant-a", "owner-1", "private policy", List.of(), 5, 1000),
                new QueryCacheKey("tenant-a", "actor-2", "private policy", List.of(), 5, 1000)
        );
    }

    @Test
    void queryFlowStoresContextAndAnswerToolOutputsBySessionId() {
        RecordingToolOutputStore toolOutputStore = new RecordingToolOutputStore();
        QueryOrchestrationService service = service(
                new LocalTemplateAnswerGenerator(),
                Duration.ofSeconds(5),
                new NoOpRetrievalCacheService(),
                toolOutputStore
        );
        ContextBuildResult context = context("security policy", retrievalResult("security policy"));

        when(contextBuilder.build("security policy", List.of(), 5, 1000, RequestContext.defaults())).thenReturn(Mono.just(context));

        StepVerifier.create(service.execute(new QueryRequest("s1", "security policy", List.of(), 5, 1000, false)))
                .assertNext(response -> assertThat(response.answer()).contains("placeholder"))
                .verifyComplete();

        assertThat(toolOutputStore.outputs()).containsKeys("s1:context", "s1:answer");
    }

    @Test
    void streamEmitsExpectedEventFlowWithConsistentTraceId() {
        QueryOrchestrationService service = service(answerGenerator, Duration.ofSeconds(5));
        ContextBuildResult context = context("security policy", retrievalResult("security policy"));

        when(contextBuilder.build("security policy", List.of(), 5, 1000, RequestContext.defaults())).thenReturn(Mono.just(context));
        when(answerGenerator.generate("security policy", context)).thenReturn(Mono.just(generatedAnswer()));
        StepVerifier.create(service.stream(new QueryRequest("s1", "security policy", List.of(), 5, 1000, false))
                        .collectList())
                .assertNext(events -> {
                    assertThat(events).extracting(event -> event.event()).containsExactly(
                            "received",
                            "retrieving",
                            "reranking",
                            "building_context",
                            "generating",
                            "message",
                            "completed"
                    );
                    String traceId = events.get(0).data().traceId();
                    assertThat(traceId).isNotBlank();
                    assertThat(events).allSatisfy(event -> assertThat(event.data().traceId()).isEqualTo(traceId));
                })
                .verifyComplete();
    }

    @Test
    void streamEmitsErrorEventWithSameTraceIdWhenPipelineFails() {
        QueryOrchestrationService service = service(answerGenerator, Duration.ofSeconds(5));

        when(contextBuilder.build("security policy", List.of(), 5, 1000, RequestContext.defaults()))
                .thenReturn(Mono.error(new IllegalStateException("retrieval failed")));

        StepVerifier.create(service.stream(new QueryRequest("s1", "security policy", List.of(), 5, 1000, false))
                        .collectList())
                .assertNext(events -> {
                    assertThat(events).extracting(event -> event.event()).containsExactly(
                            "received",
                            "retrieving",
                            "reranking",
                            "building_context",
                            "error"
                    );
                    String traceId = events.get(0).data().traceId();
                    assertThat(events).allSatisfy(event -> assertThat(event.data().traceId()).isEqualTo(traceId));
                    assertThat(events.get(events.size() - 1).data().message()).contains("retrieval failed");
                })
                .verifyComplete();
    }

    @Test
    void contextTimeoutFallsBackToEmptyContext() {
        LocalTemplateAnswerGenerator localGenerator = new LocalTemplateAnswerGenerator();
        QueryOrchestrationService service = service(localGenerator, Duration.ofMillis(10));

        when(contextBuilder.build("security policy", List.of(), 5, 1000, RequestContext.defaults())).thenReturn(Mono.never());

        StepVerifier.create(service.execute(new QueryRequest("s1", "security policy", List.of(), 5, 1000, true)))
                .assertNext(response -> {
                    assertThat(response.answer()).contains("insufficient retrieved context");
                    assertThat(response.citations()).isEmpty();
                    assertThat(response.contextDebug()).isNotNull();
                    assertThat(response.contextDebug().debugMetadata().reranker()).isEqualTo("timeout-fallback");
                })
                .verifyComplete();
    }

    @Test
    void rejectsBlankQuestion() {
        QueryOrchestrationService service = service(new LocalTemplateAnswerGenerator(), Duration.ofSeconds(5));

        StepVerifier.create(service.execute(new QueryRequest("s1", "  ", List.of(), 5, 1000, false)))
                .expectErrorSatisfies(error -> {
                    assertThat(error).isInstanceOf(BadRequestException.class);
                    assertThat(error.getMessage()).contains("question must not be blank");
                })
                .verify();
    }

    @Test
    void rejectsInvalidTopK() {
        QueryOrchestrationService service = service(new LocalTemplateAnswerGenerator(), Duration.ofSeconds(5));

        StepVerifier.create(service.execute(new QueryRequest("s1", "security policy", List.of(), 0, 1000, false)))
                .expectErrorSatisfies(error -> {
                    assertThat(error).isInstanceOf(BadRequestException.class);
                    assertThat(error.getMessage()).contains("topK must be greater than 0");
                })
                .verify();
    }

    @Test
    void rejectsInvalidContextBudget() {
        QueryOrchestrationService service = service(new LocalTemplateAnswerGenerator(), Duration.ofSeconds(5));

        StepVerifier.create(service.execute(new QueryRequest("s1", "security policy", List.of(), 5, 0, false)))
                .expectErrorSatisfies(error -> {
                    assertThat(error).isInstanceOf(BadRequestException.class);
                    assertThat(error.getMessage()).contains("contextBudgetChars must be greater than 0");
                })
                .verify();
    }

    @Test
    void executeUsesTenantContextForContextBuildCacheKeyAndAudit() {
        QueryOrchestrationService service = service(answerGenerator, Duration.ofSeconds(5));
        ContextBuildResult context = context("security policy", retrievalResult("security policy"));
        RequestContext tenantContext = new RequestContext("tenant-a", "actor-1");

        when(contextBuilder.build("security policy", List.of(), 5, 1000, tenantContext)).thenReturn(Mono.just(context));
        when(answerGenerator.generate("security policy", context)).thenReturn(Mono.just(generatedAnswer()));

        StepVerifier.create(service.execute(
                        new QueryRequest("s1", "security policy", List.of(), 5, 1000, false),
                        "trace-tenant",
                        tenantContext
                ))
                .assertNext(response -> assertThat(response.traceId()).isEqualTo("trace-tenant"))
                .verifyComplete();

        verify(contextBuilder).build("security policy", List.of(), 5, 1000, tenantContext);
        verify(auditService).record(
                eq(tenantContext),
                eq("trace-tenant"),
                eq(AuditEventType.QUERY_EXECUTED),
                eq("query"),
                any(),
                eq(null),
                any()
        );
    }

    private QueryOrchestrationService service(AnswerGenerator generator, Duration contextTimeout) {
        QueryProperties properties = new QueryProperties();
        properties.setContextTimeout(contextTimeout);
        properties.setAnswerTimeout(Duration.ofSeconds(5));
        return new QueryOrchestrationService(
                contextBuilder,
                generator,
                new InMemorySessionStateService(),
                new NoOpRetrievalCacheService(),
                new NoOpToolOutputStore(),
                auditService,
                properties
        );
    }

    private QueryOrchestrationService service(
            AnswerGenerator generator,
            Duration contextTimeout,
            RetrievalCacheService retrievalCacheService,
            ToolOutputStore toolOutputStore
    ) {
        QueryProperties properties = new QueryProperties();
        properties.setContextTimeout(contextTimeout);
        properties.setAnswerTimeout(Duration.ofSeconds(5));
        return new QueryOrchestrationService(
                contextBuilder,
                generator,
                new InMemorySessionStateService(),
                retrievalCacheService,
                toolOutputStore,
                auditService,
                properties
        );
    }

    private GeneratedAnswer generatedAnswer() {
        return new GeneratedAnswer(
                "LocalTemplateAnswerGenerator placeholder answer",
                "local-template",
                List.of("local placeholder")
        );
    }

    private ContextBuildResult context(String query, HybridRetrievalResult retrievalResult) {
        UUID documentId = UUID.randomUUID();
        return new ContextBuildResult(
                query,
                List.of(),
                List.of(),
                List.of(),
                List.of(new Citation(
                        1,
                        "[C1]",
                        documentId,
                        "policy.txt",
                        UUID.randomUUID(),
                        UUID.randomUUID(),
                        0,
                        null,
                        0,
                        20,
                        "security policy"
                )),
                "[C1] policy.txt\nSecurity policy access controls.",
                new ContextDebugMetadata("deterministic-heuristic", 1, 1, 1, 1, 1000, 40, 0, 0),
                retrievalResult
        );
    }

    private HybridRetrievalResult retrievalResult(String query) {
        return new HybridRetrievalResult(query, List.of(), List.of(), List.of());
    }

    private static class MapRetrievalCacheService implements RetrievalCacheService {

        private final Map<QueryCacheKey, ContextBuildResult> cache = new ConcurrentHashMap<>();

        @Override
        public Mono<ContextBuildResult> get(QueryCacheKey key) {
            return Mono.justOrEmpty(cache.get(key));
        }

        @Override
        public Mono<Void> put(QueryCacheKey key, ContextBuildResult value) {
            return Mono.fromRunnable(() -> cache.put(key, value));
        }

        List<QueryCacheKey> keys() {
            return List.copyOf(cache.keySet());
        }
    }

    private static class RecordingToolOutputStore implements ToolOutputStore {

        private final Map<String, Object> outputs = new ConcurrentHashMap<>();

        @Override
        public Mono<Void> save(String traceId, String key, Object value) {
            return Mono.fromRunnable(() -> outputs.put("%s:%s".formatted(traceId, key), value));
        }

        @Override
        public Mono<Object> get(String traceId, String key) {
            return Mono.justOrEmpty(outputs.get("%s:%s".formatted(traceId, key)));
        }

        Map<String, Object> outputs() {
            return outputs;
        }
    }
}
