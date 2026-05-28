package com.nexusagent.agent.application;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import com.nexusagent.agent.api.AgentQueryRequest;
import com.nexusagent.agent.api.AgentQueryResponse;
import com.nexusagent.agent.domain.AgentWorkflowStatus;
import com.nexusagent.agent.domain.CritiqueOutcome;
import com.nexusagent.agent.domain.CritiqueResult;
import com.nexusagent.agent.domain.ExecutionResult;
import com.nexusagent.agent.domain.Plan;
import com.nexusagent.agent.domain.PlanAction;
import com.nexusagent.agent.domain.PlanStep;
import com.nexusagent.common.context.RequestContext;
import com.nexusagent.common.error.BadRequestException;
import com.nexusagent.context.domain.Citation;
import com.nexusagent.enterprise.audit.AuditEventType;
import com.nexusagent.enterprise.audit.AuditService;
import com.nexusagent.query.api.QueryRequest;
import com.nexusagent.query.api.QueryResponse;
import com.nexusagent.query.application.QueryOrchestrationService;
import com.nexusagent.query.application.ToolOutputStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

@Service
public class AgentOrchestrator {

    private static final Logger log = LoggerFactory.getLogger(AgentOrchestrator.class);
    private static final String PLANNER_NAME = "deterministic-rule-planner";

    private final QueryOrchestrationService queryOrchestrationService;
    private final ToolOutputStore toolOutputStore;
    private final AuditService auditService;

    public AgentOrchestrator(
            QueryOrchestrationService queryOrchestrationService,
            ToolOutputStore toolOutputStore,
            AuditService auditService
    ) {
        this.queryOrchestrationService = queryOrchestrationService;
        this.toolOutputStore = toolOutputStore;
        this.auditService = auditService;
    }

    public Mono<AgentQueryResponse> query(AgentQueryRequest request) {
        return query(request, RequestContext.defaults(), UUID.randomUUID().toString());
    }

    public Mono<AgentQueryResponse> query(AgentQueryRequest request, RequestContext context, String traceId) {
        return Mono.defer(() -> {
            RequestContext effectiveContext = context == null ? RequestContext.defaults() : context;
            Plan plan = createPlan(request);
            String effectiveTraceId = traceId == null || traceId.isBlank() ? UUID.randomUUID().toString() : traceId.trim();
            String outputScope = outputScope(request.sessionId(), effectiveTraceId);

            return saveOutput(outputScope, "agent-plan", plan)
                    .then(executePlan(request, plan, effectiveTraceId, effectiveContext))
                    .flatMap(bundle -> {
                        CritiqueResult critique = critique(
                                plan,
                                bundle.executionResult(),
                                bundle.answer(),
                                bundle.citations()
                        );
                        AgentWorkflowStatus workflowStatus = workflowStatus(critique);
                        AgentQueryResponse response = toResponse(request, plan, bundle, critique, workflowStatus);
                        return saveOutput(outputScope, "agent-execution", bundle.executionResult())
                                .then(saveOutput(outputScope, "agent-critique", critique))
                                .then(auditAgentQuery(request, effectiveContext, response))
                                .doOnSuccess(ignored -> log.info(
                                        "agent_query_completed traceId={} tenantId={} actorId={} workflowStatus={} citationCount={}",
                                        response.traceId(),
                                        effectiveContext.tenantId(),
                                        effectiveContext.actorId(),
                                        response.workflowStatus(),
                                        response.citations().size()
                                ))
                                .thenReturn(response);
                    });
        });
    }

    public Plan createPlan(AgentQueryRequest request) {
        validate(request);
        String normalizedQuestion = request.question().trim();
        if (isDirectLocalRequest(normalizedQuestion)) {
            return new Plan(
                    PLANNER_NAME,
                    "The request is conversational and does not need document retrieval.",
                    List.of(new PlanStep(
                            1,
                            PlanAction.GENERATE_ANSWER,
                            "Return a deterministic local placeholder response."
                    ))
            );
        }
        if (isLowInformationQuestion(normalizedQuestion)) {
            return new Plan(
                    PLANNER_NAME,
                    "The question does not contain enough information to perform useful retrieval.",
                    List.of(new PlanStep(
                            1,
                            PlanAction.FALLBACK_INSUFFICIENT_CONTEXT,
                            "Return an explicit insufficient-context fallback without retrieval."
                    ))
            );
        }
        return new Plan(
                PLANNER_NAME,
                "The request should be answered from retrieved document context.",
                List.of(
                        new PlanStep(
                                1,
                                PlanAction.RETRIEVE_CONTEXT,
                                "Call the existing query pipeline to retrieve context and citations."
                        ),
                        new PlanStep(
                                2,
                                PlanAction.GENERATE_ANSWER,
                                "Generate the local placeholder answer from the retrieved context."
                        )
                )
        );
    }

    public CritiqueResult critique(
            Plan plan,
            ExecutionResult executionResult,
            String answer,
            List<Citation> citations
    ) {
        List<Citation> safeCitations = citations == null ? List.of() : List.copyOf(citations);
        if (executionResult.retrievalUsed() && isInsufficientContextAnswer(answer)) {
            return new CritiqueResult(
                    CritiqueOutcome.MISSING_CONTEXT,
                    false,
                    List.of("Retrieval was requested, but the answer reports insufficient retrieved context.")
            );
        }
        if (executionResult.fallbackUsed()) {
            return new CritiqueResult(
                    CritiqueOutcome.FALLBACK_USED,
                    false,
                    List.of("The workflow used an explicit fallback instead of making unsupported claims.")
            );
        }
        if (executionResult.retrievalUsed() && safeCitations.isEmpty()) {
            return new CritiqueResult(
                    CritiqueOutcome.MISSING_CITATIONS,
                    false,
                    List.of("Retrieval was used, but no citations were returned.")
            );
        }
        if (executionResult.retrievalUsed() && !answerMentionsAnyCitation(answer, safeCitations)) {
            return new CritiqueResult(
                    CritiqueOutcome.MISSING_CITATIONS,
                    false,
                    List.of("Retrieval returned citations, but the answer did not reference any citation marker.")
            );
        }
        if (!plan.includes(PlanAction.RETRIEVE_CONTEXT)) {
            return new CritiqueResult(
                    CritiqueOutcome.PASS,
                    false,
                    List.of("No retrieval was required, so citation grounding was not expected.")
            );
        }
        return new CritiqueResult(
                CritiqueOutcome.PASS,
                true,
                List.of("The answer includes retrieved citations and passed deterministic grounding checks.")
        );
    }

    private Mono<ExecutionBundle> executePlan(
            AgentQueryRequest request,
            Plan plan,
            String traceId,
            RequestContext context
    ) {
        if (plan.includes(PlanAction.FALLBACK_INSUFFICIENT_CONTEXT)) {
            String answer = "Agent workflow fallback: insufficient question detail. I cannot provide a grounded answer without a clearer document-related question.";
            ExecutionResult executionResult = new ExecutionResult(
                    traceId,
                    List.of(PlanAction.FALLBACK_INSUFFICIENT_CONTEXT),
                    false,
                    true,
                    "rule-based-fallback",
                    null,
                    0,
                    List.of("No retrieval was executed because the planner selected fallback.")
            );
            return Mono.just(new ExecutionBundle(answer, List.of(), executionResult, null));
        }

        if (!plan.includes(PlanAction.RETRIEVE_CONTEXT)) {
            String answer = "Agent workflow local response: this deterministic MVP can handle document-grounded questions through retrieval, but this conversational request did not require document context.";
            ExecutionResult executionResult = new ExecutionResult(
                    traceId,
                    List.of(PlanAction.GENERATE_ANSWER),
                    false,
                    false,
                    "rule-based-direct-placeholder",
                    null,
                    0,
                    List.of("No retrieval was executed because the planner selected direct local response.")
            );
            return Mono.just(new ExecutionBundle(answer, List.of(), executionResult, null));
        }

        QueryRequest queryRequest = new QueryRequest(
                request.sessionId(),
                request.question(),
                request.documentIds(),
                request.topK(),
                request.contextBudgetChars(),
                true
        );
        return queryOrchestrationService.execute(queryRequest, traceId, context)
                .map(queryResponse -> {
                    List<Citation> citations = queryResponse.citations() == null
                            ? List.of()
                            : List.copyOf(queryResponse.citations());
                    ExecutionResult executionResult = new ExecutionResult(
                            queryResponse.traceId(),
                            List.of(PlanAction.RETRIEVE_CONTEXT, PlanAction.GENERATE_ANSWER),
                            true,
                            isInsufficientContextAnswer(queryResponse.answer()),
                            "query-orchestration-service",
                            queryResponse.retrievalCacheStatus(),
                            citations.size(),
                            List.of("Executed the existing query pipeline for retrieval, context construction, and local answer generation.")
                    );
                    return new ExecutionBundle(queryResponse.answer(), citations, executionResult, queryResponse);
                });
    }

    private AgentQueryResponse toResponse(
            AgentQueryRequest request,
            Plan plan,
            ExecutionBundle bundle,
            CritiqueResult critique,
            AgentWorkflowStatus workflowStatus
    ) {
        boolean debug = Boolean.TRUE.equals(request.debug());
        return new AgentQueryResponse(
                bundle.executionResult().traceId(),
                bundle.answer(),
                bundle.citations(),
                workflowStatus,
                debug ? plan : null,
                debug ? bundle.executionResult() : null,
                debug ? critique : null,
                debug ? bundle.queryResponse() : null
        );
    }

    private AgentWorkflowStatus workflowStatus(CritiqueResult critique) {
        return switch (critique.outcome()) {
            case PASS -> AgentWorkflowStatus.COMPLETED;
            case FALLBACK_USED, MISSING_CONTEXT -> AgentWorkflowStatus.COMPLETED_WITH_FALLBACK;
            case MISSING_CITATIONS -> AgentWorkflowStatus.CRITIQUE_FAILED;
        };
    }

    private Mono<Void> auditAgentQuery(
            AgentQueryRequest request,
            RequestContext context,
            AgentQueryResponse response
    ) {
        return auditService.record(
                context,
                response.traceId(),
                AuditEventType.AGENT_QUERY_EXECUTED,
                "agent_query",
                UUID.nameUUIDFromBytes(response.traceId().getBytes(java.nio.charset.StandardCharsets.UTF_8)),
                null,
                Map.of(
                        "questionHash", auditService.hashSensitiveValue(request.question()),
                        "documentIdCount", request.documentIds() == null ? 0 : request.documentIds().size(),
                        "workflowStatus", response.workflowStatus().name(),
                        "citationCount", response.citations().size()
                )
        );
    }

    private void validate(AgentQueryRequest request) {
        if (request.question() == null || request.question().isBlank()) {
            throw new BadRequestException("question must not be blank");
        }
        if (request.topK() != null && request.topK() < 1) {
            throw new BadRequestException("topK must be greater than 0");
        }
        if (request.contextBudgetChars() != null && request.contextBudgetChars() < 1) {
            throw new BadRequestException("contextBudgetChars must be greater than 0");
        }
    }

    private boolean isLowInformationQuestion(String question) {
        long alphaNumericCount = question.chars()
                .filter(Character::isLetterOrDigit)
                .count();
        return alphaNumericCount < 3;
    }

    private boolean isDirectLocalRequest(String question) {
        String normalized = question.trim().toLowerCase(Locale.ROOT);
        return normalized.equals("hi")
                || normalized.equals("hello")
                || normalized.equals("hey")
                || normalized.equals("thanks")
                || normalized.equals("thank you")
                || normalized.equals("help");
    }

    private boolean isInsufficientContextAnswer(String answer) {
        return answer == null || answer.toLowerCase(Locale.ROOT).contains("insufficient retrieved context");
    }

    private boolean answerMentionsAnyCitation(String answer, List<Citation> citations) {
        if (answer == null || answer.isBlank()) {
            return false;
        }
        return citations.stream().anyMatch(citation -> answer.contains(citation.citationMarker()));
    }

    private String outputScope(String sessionId, String traceId) {
        if (sessionId == null || sessionId.isBlank()) {
            return traceId;
        }
        return sessionId;
    }

    private Mono<Void> saveOutput(String scope, String key, Object value) {
        return toolOutputStore.save(scope, key, value)
                .onErrorResume(error -> {
                    log.warn("Agent workflow output write failed for scope {} key {}: {}", scope, key, error.getMessage());
                    return Mono.empty();
                });
    }

    private record ExecutionBundle(
            String answer,
            List<Citation> citations,
            ExecutionResult executionResult,
            QueryResponse queryResponse
    ) {

        private ExecutionBundle {
            citations = List.copyOf(citations);
        }
    }
}
