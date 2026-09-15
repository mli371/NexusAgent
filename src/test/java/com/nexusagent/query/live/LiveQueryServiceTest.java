package com.nexusagent.query.live;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Stream;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexusagent.common.context.RequestContext;
import com.nexusagent.common.error.OperationException;
import com.nexusagent.context.application.ContextBuilder;
import com.nexusagent.context.application.ContextProperties;
import com.nexusagent.context.domain.*;
import com.nexusagent.enterprise.audit.AuditService;
import com.nexusagent.model.OpenAiProperties;
import com.nexusagent.query.api.QueryRequest;
import com.nexusagent.query.api.QueryResponse;
import com.nexusagent.query.api.QueryStreamEvent;
import com.nexusagent.query.application.*;
import com.nexusagent.retrieval.application.RetrievalProperties;
import com.nexusagent.retrieval.domain.RetrievalSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.http.HttpStatus;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

class LiveQueryServiceTest {
    static final UUID DOC = UUID.randomUUID();
    static final RequestContext OWNER = RequestContext.fromHeaders("tenant-a", "alice");
    final ContextBuilder contexts = mock(ContextBuilder.class);
    final OpenAiAnswerGenerator answers = mock(OpenAiAnswerGenerator.class);
    final LiveQueryGuard guard = mock(LiveQueryGuard.class);
    final SessionStateService sessions = mock(SessionStateService.class);
    final ToolOutputStore tools = mock(ToolOutputStore.class);
    final AuditService audit = mock(AuditService.class);
    final QueryProperties properties = new QueryProperties();
    final OpenAiProperties openAi = new OpenAiProperties();
    final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    LiveQueryService service;
    ContextBuildResult evidence;

    @BeforeEach
    void setUp() {
        evidence = evidence();
        when(guard.access(any())).thenReturn(Mono.empty());
        when(guard.readiness(any())).thenReturn(Mono.empty());
        when(guard.evidence(any(), any())).thenReturn(Mono.empty());
        when(contexts.build(anyString(), anyList(), anyInt(), anyInt(), any())).thenReturn(Mono.just(evidence));
        when(answers.name()).thenReturn("openai-responses");
        when(answers.generate(anyString(), any())).thenReturn(Mono.just(answer("Synthetic policy [C1]", List.of("[C1]"))));
        when(sessions.recordStarted(anyString(), anyString(), anyString())).thenReturn(Mono.empty());
        when(sessions.recordCompleted(anyString(), anyString())).thenReturn(Mono.empty());
        when(sessions.recordFailed(anyString(), anyString(), anyString())).thenReturn(Mono.empty());
        when(tools.save(anyString(), anyString(), any())).thenReturn(Mono.empty());
        when(audit.record(any(), anyString(), any(), anyString(), any(), any(), anyMap())).thenReturn(Mono.empty());
        var cachedContexts = new LiveContextService(contexts, guard, mock(LiveContextSnapshotRepository.class),
                LiveContextCache.disabled(), new com.nexusagent.embeddings.domain.EmbeddingModelInfo("openai", "test", 384),
                new RetrievalProperties(), mapper);
        service = new LiveQueryService(cachedContexts, answers, guard, sessions, tools, audit, properties,
                openAi, new RetrievalProperties(), new ContextProperties(), mapper);
    }

    @Test
    void publicResponseContainsOnlyUsedCitationsAndProviderMetadata() throws Exception {
        QueryResponse result = execute(false);
        assertThat(result.citations()).extracting(Citation::citationMarker).containsExactly("[C1]");
        assertThat(result.answerProvider()).isEqualTo("openai");
        assertThat(result.answerStatus()).isEqualTo("answered");
        String json = mapper.writeValueAsString(result);
        assertThat(json).doesNotContain("finalContextText", "retrievalDebug", "contextDebug", "stages", "limitations", "retrievalCacheStatus");
        verify(answers, times(1)).generate(anyString(), any());
        verify(guard, times(2)).evidence(any(), any());
    }

    @Test
    void debugContainsContextStagesAndExplicitCacheBypass() {
        QueryResponse result = execute(true);
        assertThat(result.contextDebug()).isNotNull();
        assertThat(result.finalContextText()).isEqualTo(evidence.finalContextText());
        assertThat(result.retrievalCacheStatus()).isEqualTo("bypassed");
        assertThat(result.stages()).anySatisfy(stage -> {
            assertThat(stage.stage()).isEqualTo("cache_lookup");
            assertThat(stage.status()).isEqualTo("skipped");
        });
    }

    @Test
    void emptyContextDoesNotCallAnswerModelOrInventCitations() {
        when(contexts.build(anyString(), anyList(), anyInt(), anyInt(), any())).thenReturn(Mono.just(emptyEvidence()));
        QueryResponse result = execute(true);
        assertThat(result.answerStatus()).isEqualTo("insufficient_context");
        assertThat(result.answer()).contains("Insufficient");
        assertThat(result.citations()).isEmpty();
        verify(answers, never()).generate(anyString(), any());
    }

    @Test
    void unavailableStateAndAuditAreIgnoredAndNoRawQuestionOrAnswerIsStored() {
        when(tools.save(anyString(), anyString(), any())).thenReturn(Mono.error(new IllegalStateException("secret")));
        when(sessions.recordStarted(anyString(), anyString(), anyString())).thenReturn(Mono.error(new IllegalStateException("secret")));
        when(audit.record(any(), anyString(), any(), anyString(), any(), any(), anyMap())).thenReturn(Mono.error(new IllegalStateException("secret")));
        assertThat(execute(false).answerStatus()).isEqualTo("answered");
        verify(sessions).recordStarted(startsWith("live-"), startsWith("live-"), matches("questionHash=[a-f0-9]{64}"));
        var captor = org.mockito.ArgumentCaptor.forClass(Object.class);
        verify(tools).save(startsWith("live-"), eq("query-summary"), captor.capture());
        assertThat(captor.getValue().toString()).doesNotContain("policy", "context", "Synthetic");
    }

    @Test
    void stateThatNeverCompletesHasBoundedWait() {
        when(sessions.recordStarted(anyString(), anyString(), anyString())).thenReturn(Mono.never());
        StepVerifier.withVirtualTime(() -> service.execute(request(true), "trace-1", OWNER))
                .thenAwait(Duration.ofSeconds(3)).assertNext(result -> assertThat(result.answerStatus()).isEqualTo("answered"))
                .verifyComplete();
    }

    @Test
    void scopeIsTenantActorAndIdentifierSpecificAndUnambiguous() {
        LiveQueryInput input = input(OWNER);
        String original = LiveQueryService.scope(input, "session");
        assertThat(original).isNotEqualTo(LiveQueryService.scope(input(RequestContext.fromHeaders("tenant-b", "alice")), "session"));
        assertThat(original).isNotEqualTo(LiveQueryService.scope(input(RequestContext.fromHeaders("tenant-a", "bob")), "session"));
        assertThat(original).isNotEqualTo(LiveQueryService.scope(input, "other"));
        assertThat(LiveQueryService.scope(input(RequestContext.fromHeaders("a:b", "c")), "x"))
                .isNotEqualTo(LiveQueryService.scope(input(RequestContext.fromHeaders("a", "b:c")), "x"));
    }

    @Test
    void contextTimeoutIsErrorWithoutTemplateFallbackOrPipelineRetry() {
        when(contexts.build(anyString(), anyList(), anyInt(), anyInt(), any())).thenReturn(Mono.never());
        StepVerifier.withVirtualTime(() -> service.execute(request(true), "trace-timeout", OWNER))
                .thenAwait(Duration.ofSeconds(46)).expectErrorSatisfies(error -> {
                    assertThat(((QueryFailure) error).status()).isEqualTo(HttpStatus.GATEWAY_TIMEOUT);
                    assertThat(((QueryFailure) error).traceId()).isEqualTo("trace-timeout");
                }).verify();
        verify(contexts, times(1)).build(anyString(), anyList(), anyInt(), anyInt(), any());
        verify(answers, never()).generate(anyString(), any());
    }

    @Test
    void answerTimeoutNeverFallsBackAndOnlyCallsOnce() {
        when(answers.generate(anyString(), any())).thenReturn(Mono.never());
        StepVerifier.withVirtualTime(() -> service.execute(request(false), "trace-1", OWNER))
                .thenAwait(Duration.ofSeconds(91)).expectError(QueryFailure.class).verify();
        verify(answers, times(1)).generate(anyString(), any());
    }

    @Test
    void permissionChangeBeforeModelStopsEgress() {
        when(guard.evidence(any(), any())).thenReturn(Mono.error(LiveQueryGuard.changed()));
        StepVerifier.create(service.execute(request(false), "trace-1", OWNER)).expectError(QueryFailure.class).verify();
        verify(answers, never()).generate(anyString(), any());
    }

    @Test
    void permissionChangeAfterModelSuppressesAnswerAndDebugContext() {
        when(guard.evidence(any(), any())).thenReturn(Mono.empty(), Mono.error(LiveQueryGuard.changed()));
        StepVerifier.create(service.stream(request(true), "trace-1", OWNER).map(event -> event.data()).collectList())
                .assertNext(events -> {
                    assertThat(events).noneMatch(event -> event.response() != null || "message".equals(event.type()));
                    assertThat(events.get(events.size() - 1).type()).isEqualTo("error");
                    assertThat(events.get(events.size() - 1).code()).isEqualTo("DOCUMENT_CHANGED");
                }).verifyComplete();
    }

    @Test
    void invalidCitationFailsWithoutEmittingAnswer() {
        when(answers.generate(anyString(), any())).thenReturn(Mono.just(answer("Unsupported [C9]", List.of("[C9]"))));
        StepVerifier.create(service.execute(request(false), "trace-1", OWNER))
                .expectErrorSatisfies(error -> assertThat(((QueryFailure) error).code()).isEqualTo("INVALID_MODEL_RESPONSE")).verify();
    }

    @Test
    void sseHasOneExecutionConsistentTraceAndOrderedTerminalEvents() {
        var events = service.stream(request(true), "trace-sse", OWNER).map(event -> event.data()).collectList().block();
        assertThat(events).allMatch(event -> event.traceId().equals("trace-sse"));
        assertThat(events.get(0).type()).isEqualTo("received");
        assertThat(events.subList(events.size() - 2, events.size())).extracting(QueryStreamEvent::type).containsExactly("message", "completed");
        assertThat(events.stream().filter(event -> event.stage() != null).map(event -> event.stage().sequence()).toList()).isSorted().doesNotHaveDuplicates();
        verify(contexts, times(1)).build(anyString(), anyList(), anyInt(), anyInt(), any());
        verify(answers, times(1)).generate(anyString(), any());
    }

    @Test
    void sseUnexpectedFailureIsSanitizedWithoutCompletedOrMessage() {
        when(answers.generate(anyString(), any())).thenReturn(Mono.error(new IllegalStateException("api-key=secret prompt=private")));
        var events = service.stream(request(false), "trace-fail", OWNER).map(event -> event.data()).collectList().block();
        assertThat(events).noneMatch(event -> "completed".equals(event.type()) || "message".equals(event.type()));
        assertThat(events.get(events.size() - 1).code()).isEqualTo("QUERY_UNAVAILABLE");
        assertThat(events.toString()).doesNotContain("secret", "private");
        assertThat(events.stream().filter(event -> event.stage() != null)).allMatch(event -> event.stage().summary() == null);
    }

    @Test
    void preflightFailureEmitsNoSseEventsAndNeverRunsRetrieval() {
        when(guard.readiness(any())).thenReturn(Mono.error(OperationException.modelMismatch()));
        StepVerifier.create(service.stream(request(true), "trace-preflight", OWNER))
                .expectErrorSatisfies(error -> assertThat(((QueryFailure) error).status()).isEqualTo(HttpStatus.CONFLICT)).verify();
        verifyNoInteractions(contexts);
    }

    @Test
    void cancellingSseCancelsInFlightModelPublisher() {
        AtomicBoolean cancelled = new AtomicBoolean();
        when(answers.generate(anyString(), any())).thenReturn(Mono.<GeneratedAnswer>never().doOnCancel(() -> cancelled.set(true)));
        StepVerifier.create(service.stream(request(true), "trace-cancel", OWNER))
                .thenConsumeWhile(event -> event.data().stage() == null || !event.data().stage().stage().equals("answer_generation"))
                .thenCancel().verify(Duration.ofSeconds(5));
        assertThat(cancelled).isTrue();
    }

    @ParameterizedTest
    @MethodSource("invalidRequests")
    void rejectsInvalidInputsBeforeAnyDocumentOrModelWork(QueryRequest request) {
        StepVerifier.create(service.execute(request, "trace-bad", OWNER))
                .expectErrorSatisfies(error -> assertThat(((QueryFailure) error).status()).isEqualTo(HttpStatus.BAD_REQUEST)).verify();
        verifyNoInteractions(guard, contexts);
        verify(answers, never()).generate(anyString(), any());
    }

    static Stream<QueryRequest> invalidRequests() {
        return Stream.of(new QueryRequest("s", " ", List.of(DOC), 1, 100, false),
                new QueryRequest("s", "x".repeat(2001), List.of(DOC), 1, 100, false),
                new QueryRequest("s", "policy", List.of(), 1, 100, false, "documents"),
                new QueryRequest("s", "policy", List.of(DOC), 1, 100, false, "library"),
                new QueryRequest("s", "policy", List.of(), 1, 100, false, "invalid"),
                new QueryRequest("s", "policy", java.util.Collections.nCopies(11, DOC), 1, 100, false),
                new QueryRequest("s", "policy", List.of(DOC), 0, 100, false),
                new QueryRequest("s", "policy", List.of(DOC), 51, 100, false),
                new QueryRequest("s", "policy", List.of(DOC), 1, 0, false),
                new QueryRequest("s", "policy", List.of(DOC), 1, 12001, false),
                new QueryRequest("x".repeat(121), "policy", List.of(DOC), 1, 100, false));
    }

    private QueryResponse execute(boolean debug) { return service.execute(request(debug), "trace-1", OWNER).block(Duration.ofSeconds(5)); }
    static QueryRequest request(boolean debug) { return new QueryRequest("session-1", "policy", List.of(DOC), 5, 1000, debug); }
    private LiveQueryInput input(RequestContext context) {
        return LiveQueryInput.from(request(false), "trace-1", context, new RetrievalProperties(), new ContextProperties());
    }
    static GeneratedAnswer answer(String text, List<String> used) {
        return new GeneratedAnswer(text, "openai-responses", List.of("Structural citation validation only"), "answered", used);
    }
    static ContextBuildResult evidence() {
        UUID p1 = UUID.randomUUID(), c1 = UUID.randomUUID(), p2 = UUID.randomUUID(), c2 = UUID.randomUUID();
        return new ContextBuildResult("policy", List.of(),
                List.of(new SelectedChildChunk(c1, p1, DOC, "synthetic.md", 0, 0, 16, "Synthetic policy", RetrievalSource.BOTH, 1, 1, "match"),
                        new SelectedChildChunk(c2, p2, DOC, "synthetic.md", 1, 17, 33, "Synthetic policy", RetrievalSource.BOTH, 2, 0.5, "match")),
                List.of(new ExpandedParentContext(p1, DOC, "synthetic.md", 0, 0, 16, "Synthetic policy", false, 16, List.of(c1)),
                        new ExpandedParentContext(p2, DOC, "synthetic.md", 1, 17, 33, "Synthetic policy", false, 16, List.of(c2))),
                List.of(new Citation(1, "[C1]", DOC, "synthetic.md", p1, c1, 0, null, 0, 16, "Synthetic policy"),
                        new Citation(2, "[C2]", DOC, "synthetic.md", p2, c2, 1, null, 17, 33, "Synthetic policy")),
                "[C1] Synthetic policy\n[C2] Synthetic policy",
                new ContextDebugMetadata("heuristic", 2, 2, 2, 2, 1000, 32, 0, 0));
    }
    static ContextBuildResult emptyEvidence() {
        return new ContextBuildResult("policy", List.of(), List.of(), List.of(), List.of(), "",
                new ContextDebugMetadata("heuristic", 0, 0, 0, 0, 1000, 0, 0, 0));
    }
}
