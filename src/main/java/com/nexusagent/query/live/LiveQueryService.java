package com.nexusagent.query.live;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexusagent.common.context.RequestContext;
import com.nexusagent.common.error.OperationException;
import com.nexusagent.common.observation.StageObservation;
import com.nexusagent.context.api.ContextDebugResponse;
import com.nexusagent.context.application.ContextProperties;
import com.nexusagent.context.domain.ContextBuildResult;
import com.nexusagent.enterprise.audit.AuditEventType;
import com.nexusagent.enterprise.audit.AuditService;
import com.nexusagent.model.OpenAiProperties;
import com.nexusagent.query.api.QueryRequest;
import com.nexusagent.query.api.QueryResponse;
import com.nexusagent.query.api.QueryStreamEvent;
import com.nexusagent.query.api.QueryScopeSummary;
import com.nexusagent.query.application.GeneratedAnswer;
import com.nexusagent.query.application.OpenAiAnswerGenerator;
import com.nexusagent.query.application.QueryProperties;
import com.nexusagent.query.application.SessionStateService;
import com.nexusagent.query.application.ToolOutputStore;
import com.nexusagent.retrieval.api.RetrievalDebugResponse;
import com.nexusagent.retrieval.application.RetrievalProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.codec.ServerSentEvent;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/** Fixed Java RAG pipeline; neither Pi nor the model chooses tools or write actions here. */
public class LiveQueryService {
    private static final Logger log = LoggerFactory.getLogger(LiveQueryService.class);
    private final LiveContextService contexts;
    private final OpenAiAnswerGenerator answers;
    private final LiveQueryGuard guard;
    private final SessionStateService sessions;
    private final ToolOutputStore tools;
    private final AuditService audit;
    private final QueryProperties properties;
    private final OpenAiProperties openAi;
    private final RetrievalProperties retrieval;
    private final ContextProperties contextProperties;
    private final ObjectMapper mapper;
    private final AnswerCitationValidator citations = new AnswerCitationValidator();

    public LiveQueryService(LiveContextService contexts, OpenAiAnswerGenerator answers, LiveQueryGuard guard,
                            SessionStateService sessions, ToolOutputStore tools, AuditService audit,
                            QueryProperties properties, OpenAiProperties openAi, RetrievalProperties retrieval,
                            ContextProperties contextProperties, ObjectMapper mapper) {
        this.contexts = contexts;
        this.answers = answers;
        this.guard = guard;
        this.sessions = sessions;
        this.tools = tools;
        this.audit = audit;
        this.properties = properties;
        this.openAi = openAi;
        this.retrieval = retrieval;
        this.contextProperties = contextProperties;
        this.mapper = mapper;
    }

    public String modelName() { return openAi.getAnswerModel(); }
    public String cacheMode() { return contexts.mode(); }

    public Mono<QueryResponse> execute(QueryRequest request, String traceId, RequestContext context) {
        return prepare(request, traceId, context).flatMap(prepared -> pipeline(prepared)
                .doFinally(ignored -> prepared.trace().close()));
    }

    public Flux<ServerSentEvent<QueryStreamEvent>> stream(QueryRequest request, String traceId, RequestContext context) {
        // Preflight completes before subscribing to the buffered events, so a 404/409 is still HTTP, not SSE.
        return prepare(request, traceId, context).flatMapMany(prepared -> Flux.merge(
                prepared.trace().events(),
                pipeline(prepared).doOnNext(prepared.trace()::complete)
                        .onErrorResume(error -> {
                            prepared.trace().fail(error);
                            return Mono.empty();
                        }).thenMany(Flux.<ServerSentEvent<QueryStreamEvent>>empty())
        ).doFinally(ignored -> prepared.trace().close()));
    }

    private Mono<Prepared> prepare(QueryRequest request, String suppliedTrace, RequestContext context) {
        return Mono.defer(() -> {
            String traceId = suppliedTrace == null || suppliedTrace.isBlank() ? UUID.randomUUID().toString() : suppliedTrace.trim();
            // Never echo a malformed/unbounded trace header in an error response.
            String errorTrace = traceId.length() <= 120 && traceId.matches("[A-Za-z0-9._:-]+")
                    ? traceId : UUID.randomUUID().toString();
            return Mono.fromSupplier(() -> LiveQueryInput.from(request, traceId, context, retrieval, contextProperties))
                    .flatMap(input -> {
                        QueryTrace trace = new QueryTrace(input.traceId(), input.debug(), mapper);
                        Mono<LiveQueryGuard.ScopedQuery> selected = "library".equals(input.scope())
                                ? StageObservation.observe("access_check", () -> guard.librarySnapshot(input),
                                        rows -> Map.of("accessibleDocumentCount", rows.size()))
                                    .flatMap(rows -> StageObservation.observe("embedding_readiness",
                                            () -> Mono.fromSupplier(() -> guard.selectReady(input, rows)),
                                            scope -> Map.of("searchedDocumentCount", scope.summary().searchedDocumentCount(),
                                                    "excludedDocumentCount", scope.summary().excludedDocumentCount(),
                                                    "exclusions", scope.summary().exclusions())))
                                : StageObservation.observe("access_check", () -> guard.access(input), ignored -> Map.of())
                                .then(StageObservation.observe("embedding_readiness", () -> guard.readiness(input), ignored -> Map.of()))
                                .thenReturn(new LiveQueryGuard.ScopedQuery(input,
                                        new QueryScopeSummary("documents", input.documentIds().size(), input.documentIds().size(), 0, Map.of())));
                        return selected
                                .timeout(properties.getLiveContextTimeout())
                                .map(scope -> new Prepared(scope.input(), trace, scope.summary()))
                                .contextWrite(values -> values.put(StageObservation.KEY, trace))
                                .doOnError(ignored -> trace.close());
                    }).onErrorMap(error -> QueryFailure.safe(errorTrace, error));
        });
    }

    private Mono<QueryResponse> pipeline(Prepared prepared) {
        LiveQueryInput input = prepared.input();
        QueryTrace trace = prepared.trace();
        String sessionKey = scope(input, input.sessionId());
        String statusKey = scope(input, input.traceId());
        return bestEffort(input, "session_started", () -> sessions.recordStarted(sessionKey, statusKey,
                        "questionHash=" + hash(input.question())))
                .then(Mono.defer(() -> contexts.load(input, trace)).timeout(properties.getLiveContextTimeout()))
                .flatMap(loaded -> {
                    ContextBuildResult result = loaded.context();
                    return contexts.assertCurrent(input, loaded).timeout(properties.getLiveContextTimeout())
                        .then(Mono.defer(() -> generate(input, trace, result))).flatMap(answer ->
                        StageObservation.observe("citation_validation", () -> Mono.fromSupplier(() -> citations.validate(answer, result)),
                                        used -> Map.of("usedCitationCount", used.size()))
                                .flatMap(used -> {
                                    Map<String, Object> summary = Map.of("schemaVersion", 1, "traceId", input.traceId(),
                                            "answerStatus", answer.status(), "citationCount", used.size(),
                                            "documentCount", input.documentIds().size(), "cacheStatus", loaded.cacheStatus());
                                    return bestEffort(input, "tool_summary", () -> tools.save(statusKey, "query-summary", summary))
                                            .then(bestEffort(input, "audit", () -> audit.record(input.context(), input.traceId(),
                                                    AuditEventType.QUERY_EXECUTED, "query",
                                                    UUID.nameUUIDFromBytes(input.traceId().getBytes(StandardCharsets.UTF_8)), null,
                                                    Map.of("questionHash", hash(input.question()),
                                                            "citationCount", used.size(), "cacheStatus", loaded.cacheStatus(), "answerStatus", answer.status()))))
                                            .then(bestEffort(input, "session_completed", () -> sessions.recordCompleted(sessionKey, statusKey)))
                                            .then(guard.evidence(input, result).timeout(properties.getLiveContextTimeout()))
                                            .then(contexts.assertCurrent(input, loaded).timeout(properties.getLiveContextTimeout()))
                                            .then(Mono.fromSupplier(() -> new QueryResponse(input.traceId(), answer.answer(), used,
                                                    input.debug() ? result.finalContextText() : null,
                                                    input.debug() && result.retrievalResult() != null ? RetrievalDebugResponse.from(result.retrievalResult()) : null,
                                                    input.debug() ? ContextDebugResponse.from(result) : null,
                                                    input.debug() ? answer.limitations() : null,
                                                    input.debug() ? loaded.cacheStatus() : null,
                                                    answer.status(), "openai", modelName(), input.debug() ? trace.history() : null, prepared.scope())));
                                }));
                })
                .doOnNext(response -> log.info("live_query_completed traceId={} tenantId={} actorId={} status={} citationCount={}",
                        input.traceId(), input.context().tenantId(), input.context().actorId(), response.answerStatus(), response.citations().size()))
                .onErrorMap(error -> QueryFailure.safe(input.traceId(), error))
                .onErrorResume(QueryFailure.class, error -> bestEffort(input, "session_failed",
                                () -> sessions.recordFailed(sessionKey, statusKey, error.code()))
                        .then(Mono.error(error)))
                .doOnError(QueryFailure.class, error -> log.warn("live_query_failed traceId={} code={}", input.traceId(), error.code()))
                .contextWrite(values -> values.put(StageObservation.KEY, trace).put(RequestContext.TRACE_HEADER, input.traceId()));
    }

    private Mono<GeneratedAnswer> generate(LiveQueryInput input, QueryTrace trace, ContextBuildResult context) {
        if (context.finalContextText().isBlank() || context.citations().isEmpty()) {
            trace.skipped("answer_generation", "insufficient_context_no_model_call");
            return Mono.just(new GeneratedAnswer("Insufficient retrieved context to answer this question.",
                    answers.name(), List.of("No usable retrieved evidence; no answer model was called."),
                    "insufficient_context", List.of()));
        }
        return StageObservation.observe("answer_generation",
                () -> answers.generate(input.question(), context)
                        .switchIfEmpty(Mono.error(OperationException.invalidModelResponse()))
                        .timeout(openAi.getAnswerTimeout()),
                answer -> Map.of("status", answer.status(), "model", modelName()));
    }

    private Mono<Void> bestEffort(LiveQueryInput input, String operation, Supplier<Mono<Void>> work) {
        return Mono.defer(work).timeout(properties.getStateTimeout()).onErrorResume(error -> {
            log.warn("live_query_side_effect_failed traceId={} operation={} errorType={}",
                    input.traceId(), operation, error.getClass().getSimpleName());
            return Mono.empty();
        });
    }

    static String scope(LiveQueryInput input, String id) {
        // Length prefixes avoid ambiguity when demo tenant/actor identifiers contain colons.
        String tenant = input.context().tenantId();
        String actor = input.context().actorId();
        return "live-" + hash(tenant.length() + ":" + tenant + actor.length() + ":" + actor + id.length() + ":" + id);
    }

    private static String hash(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 unavailable");
        }
    }

    private record Prepared(LiveQueryInput input, QueryTrace trace, QueryScopeSummary scope) { }
}
