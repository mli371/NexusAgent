package com.nexusagent.query.application;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeoutException;

import com.nexusagent.common.context.RequestContext;
import com.nexusagent.common.error.BadRequestException;
import com.nexusagent.context.api.ContextDebugResponse;
import com.nexusagent.context.application.ContextBuilder;
import com.nexusagent.context.domain.ContextBuildResult;
import com.nexusagent.context.domain.ContextDebugMetadata;
import com.nexusagent.query.api.QueryRequest;
import com.nexusagent.query.api.QueryResponse;
import com.nexusagent.query.api.QueryStreamEvent;
import com.nexusagent.retrieval.api.RetrievalDebugResponse;
import com.nexusagent.enterprise.audit.AuditEventType;
import com.nexusagent.enterprise.audit.AuditService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.stereotype.Service;
import reactor.core.Exceptions;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.util.retry.Retry;

@Service
public class QueryOrchestrationService {

    private static final Logger log = LoggerFactory.getLogger(QueryOrchestrationService.class);

    private final ContextBuilder contextBuilder;
    private final AnswerGenerator answerGenerator;
    private final SessionStateService sessionStateService;
    private final RetrievalCacheService retrievalCacheService;
    private final ToolOutputStore toolOutputStore;
    private final AuditService auditService;
    private final QueryProperties queryProperties;

    public QueryOrchestrationService(
            ContextBuilder contextBuilder,
            AnswerGenerator answerGenerator,
            SessionStateService sessionStateService,
            RetrievalCacheService retrievalCacheService,
            ToolOutputStore toolOutputStore,
            AuditService auditService,
            QueryProperties queryProperties
    ) {
        this.contextBuilder = contextBuilder;
        this.answerGenerator = answerGenerator;
        this.sessionStateService = sessionStateService;
        this.retrievalCacheService = retrievalCacheService;
        this.toolOutputStore = toolOutputStore;
        this.auditService = auditService;
        this.queryProperties = queryProperties;
    }

    public Mono<QueryResponse> execute(QueryRequest request) {
        return execute(request, UUID.randomUUID().toString(), RequestContext.defaults());
    }

    public Mono<QueryResponse> execute(QueryRequest request, String traceId) {
        return execute(request, traceId, RequestContext.defaults());
    }

    public Mono<QueryResponse> execute(QueryRequest request, RequestContext context) {
        return execute(request, UUID.randomUUID().toString(), context);
    }

    public Mono<QueryResponse> execute(QueryRequest request, String traceId, RequestContext context) {
        return Mono.defer(() -> {
            QueryInput input = normalize(request, normalizeTraceId(traceId), context);
            return runPipeline(input)
                    .map(result -> toResponse(input, result))
                    .flatMap(response -> auditQuery(input, response).thenReturn(response))
                    .doOnSuccess(response -> log.info(
                            "query_completed traceId={} tenantId={} actorId={} citationCount={} cacheStatus={}",
                            input.traceId(),
                            input.context().tenantId(),
                            input.context().actorId(),
                            response.citations().size(),
                            response.retrievalCacheStatus()
                    ))
                    .flatMap(response -> sessionStateService.recordCompleted(input.sessionId(), input.traceId())
                            .thenReturn(response))
                    .onErrorResume(error -> sessionStateService.recordFailed(
                                    input.sessionId(),
                                    input.traceId(),
                                    error.getMessage()
                            )
                            .then(Mono.error(error)));
        });
    }

    public Flux<ServerSentEvent<QueryStreamEvent>> stream(QueryRequest request) {
        return stream(request, UUID.randomUUID().toString(), RequestContext.defaults());
    }

    public Flux<ServerSentEvent<QueryStreamEvent>> stream(QueryRequest request, String traceId, RequestContext context) {
        return Flux.defer(() -> {
            QueryInput input = normalize(request, normalizeTraceId(traceId), context);
            return Flux.concat(
                            Mono.just(event("received", input.traceId(), "query received", null)),
                            sessionStateService.recordStarted(input.sessionId(), input.traceId(), input.question())
                                    .thenReturn(event("retrieving", input.traceId(), "building retrieval candidates", null)),
                            Mono.just(event("reranking", input.traceId(), "reranking fused candidates", null)),
                            Mono.just(event("building_context", input.traceId(), "expanding parent context", null)),
                            runContext(input)
                                    .flatMapMany(contextResult -> Flux.concat(
                                            toolOutputStore.save(toolOutputScope(input), "context", contextResult.context())
                                                    .thenReturn(event("generating", input.traceId(), "generating local placeholder answer", null)),
                                            runAnswer(input.question(), contextResult.context())
                                                    .flatMapMany(answer -> {
                                                        QueryPipelineResult result = new QueryPipelineResult(
                                                                contextResult.context(),
                                                                answer,
                                                                contextResult.cacheStatus()
                                                        );
                                                        QueryResponse response = toResponse(input, result);
                                                        return toolOutputStore.save(toolOutputScope(input), "answer", answer)
                                                                .then(auditQuery(input, response))
                                                                .doOnSuccess(ignored -> log.info(
                                                                        "query_stream_completed traceId={} tenantId={} actorId={} citationCount={} cacheStatus={}",
                                                                        input.traceId(),
                                                                        input.context().tenantId(),
                                                                        input.context().actorId(),
                                                                        response.citations().size(),
                                                                        response.retrievalCacheStatus()
                                                                ))
                                                                .thenMany(Flux.just(
                                                                        event("message", input.traceId(), answer.answer(), null),
                                                                        event("completed", input.traceId(), "query completed", response)
                                                                ));
                                                    })
                                    ))
                    )
                    .concatWith(sessionStateService.recordCompleted(input.sessionId(), input.traceId()).then(Mono.empty()))
                    .onErrorResume(error -> sessionStateService.recordFailed(
                                    input.sessionId(),
                                    input.traceId(),
                                    error.getMessage()
                            )
                            .thenMany(Flux.just(event("error", input.traceId(), error.getMessage(), null))));
        });
    }

    private Mono<QueryPipelineResult> runPipeline(QueryInput input) {
        return sessionStateService.recordStarted(input.sessionId(), input.traceId(), input.question())
                .then(runContext(input))
                .flatMap(contextResult -> toolOutputStore.save(toolOutputScope(input), "context", contextResult.context())
                        .then(runAnswer(input.question(), contextResult.context()))
                        .flatMap(answer -> toolOutputStore.save(toolOutputScope(input), "answer", answer)
                                .thenReturn(new QueryPipelineResult(
                                        contextResult.context(),
                                        answer,
                                        contextResult.cacheStatus()
                                ))));
    }

    private Mono<ContextPipelineResult> runContext(QueryInput input) {
        QueryCacheKey cacheKey = new QueryCacheKey(
                input.context().tenantId(),
                input.context().actorId(),
                input.question(),
                input.documentIds(),
                input.topK(),
                input.contextBudgetChars()
        );
        return retrievalCacheService.get(cacheKey)
                .map(context -> new ContextPipelineResult(context, "hit"))
                        .switchIfEmpty(Mono.defer(() -> contextBuilder.build(
                                input.question(),
                                input.documentIds(),
                                input.topK(),
                                input.contextBudgetChars(),
                                input.context()
                        )
                        .timeout(queryProperties.getContextTimeout())
                        .retryWhen(Retry.max(1).filter(error -> !(error instanceof BadRequestException)))
                        .onErrorResume(error -> {
                            Throwable unwrapped = unwrapRetryExhausted(error);
                            if (unwrapped instanceof TimeoutException) {
                                return Mono.just(timeoutFallbackContext(input));
                            }
                            return Mono.error(unwrapped);
                        })
                        .flatMap(context -> retrievalCacheService.put(cacheKey, context)
                                .thenReturn(new ContextPipelineResult(context, "miss")))));
    }

    private Mono<GeneratedAnswer> runAnswer(String question, ContextBuildResult context) {
        return answerGenerator.generate(question, context)
                .timeout(queryProperties.getAnswerTimeout())
                .onErrorResume(TimeoutException.class, error -> Mono.just(new GeneratedAnswer(
                        "LocalTemplateAnswerGenerator placeholder answer: answer generation timed out.",
                        answerGenerator.name(),
                        List.of("Answer generation timed out and returned a local fallback.")
                )));
    }

    private ContextBuildResult timeoutFallbackContext(QueryInput input) {
        return new ContextBuildResult(
                input.question(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                "",
                new ContextDebugMetadata(
                        "timeout-fallback",
                        0,
                        0,
                        0,
                        0,
                        input.contextBudgetChars() == null ? 0 : input.contextBudgetChars(),
                        0,
                        0,
                        0
                )
        );
    }

    private Throwable unwrapRetryExhausted(Throwable error) {
        if (Exceptions.isRetryExhausted(error) && error.getCause() != null) {
            return error.getCause();
        }
        return error;
    }

    private QueryResponse toResponse(QueryInput input, QueryPipelineResult result) {
        boolean debug = input.debug();
        RetrievalDebugResponse retrievalDebug = null;
        if (debug && result.context().retrievalResult() != null) {
            retrievalDebug = RetrievalDebugResponse.from(result.context().retrievalResult());
        }
        return new QueryResponse(
                input.traceId(),
                result.answer().answer(),
                result.context().citations(),
                debug ? result.context().finalContextText() : null,
                retrievalDebug,
                debug ? ContextDebugResponse.from(result.context()) : null,
                debug ? result.answer().limitations() : null,
                debug ? result.cacheStatus() : null
        );
    }

    private QueryInput normalize(QueryRequest request, String traceId, RequestContext context) {
        if (request.question() == null || request.question().isBlank()) {
            throw new BadRequestException("question must not be blank");
        }
        if (traceId == null || traceId.isBlank()) {
            throw new BadRequestException("traceId must not be blank");
        }
        if (request.topK() != null && request.topK() < 1) {
            throw new BadRequestException("topK must be greater than 0");
        }
        if (request.contextBudgetChars() != null && request.contextBudgetChars() < 1) {
            throw new BadRequestException("contextBudgetChars must be greater than 0");
        }
        return new QueryInput(
                request.sessionId(),
                traceId,
                context == null ? RequestContext.defaults() : context,
                request.question().trim(),
                request.documentIds() == null ? List.of() : List.copyOf(request.documentIds()),
                request.topK(),
                request.contextBudgetChars(),
                Boolean.TRUE.equals(request.debug())
        );
    }

    private String normalizeTraceId(String traceId) {
        if (traceId == null || traceId.isBlank()) {
            return UUID.randomUUID().toString();
        }
        return traceId.trim();
    }

    private Mono<Void> auditQuery(QueryInput input, QueryResponse response) {
        return auditService.record(
                input.context(),
                input.traceId(),
                AuditEventType.QUERY_EXECUTED,
                "query",
                traceResourceId(input.traceId()),
                null,
                Map.of(
                        "questionHash", auditService.hashSensitiveValue(input.question()),
                        "documentIdCount", input.documentIds().size(),
                        "topK", input.topK() == null ? "default" : input.topK(),
                        "contextBudgetChars", input.contextBudgetChars() == null ? "default" : input.contextBudgetChars(),
                        "citationCount", response.citations() == null ? 0 : response.citations().size(),
                        "cacheStatus", response.retrievalCacheStatus() == null ? "hidden" : response.retrievalCacheStatus()
                )
        );
    }

    private UUID traceResourceId(String traceId) {
        return UUID.nameUUIDFromBytes(traceId.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    private ServerSentEvent<QueryStreamEvent> event(
            String eventName,
            String traceId,
            String message,
            QueryResponse response
    ) {
        return ServerSentEvent.<QueryStreamEvent>builder()
                .event(eventName)
                .data(new QueryStreamEvent(eventName, traceId, message, response))
                .build();
    }

    private String toolOutputScope(QueryInput input) {
        if (input.sessionId() == null || input.sessionId().isBlank()) {
            return input.traceId();
        }
        return input.sessionId();
    }

    private record QueryInput(
            String sessionId,
            String traceId,
            RequestContext context,
            String question,
            List<UUID> documentIds,
            Integer topK,
            Integer contextBudgetChars,
            boolean debug
    ) {
    }

    private record QueryPipelineResult(
            ContextBuildResult context,
            GeneratedAnswer answer,
            String cacheStatus
    ) {
    }

    private record ContextPipelineResult(
            ContextBuildResult context,
            String cacheStatus
    ) {
    }
}
