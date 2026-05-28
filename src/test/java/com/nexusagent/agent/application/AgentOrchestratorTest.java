package com.nexusagent.agent.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.UUID;

import com.nexusagent.agent.api.AgentQueryRequest;
import com.nexusagent.agent.domain.AgentWorkflowStatus;
import com.nexusagent.agent.domain.CritiqueOutcome;
import com.nexusagent.agent.domain.ExecutionResult;
import com.nexusagent.agent.domain.Plan;
import com.nexusagent.agent.domain.PlanAction;
import com.nexusagent.common.context.RequestContext;
import com.nexusagent.context.domain.Citation;
import com.nexusagent.enterprise.audit.AuditService;
import com.nexusagent.query.api.QueryRequest;
import com.nexusagent.query.api.QueryResponse;
import com.nexusagent.query.application.QueryOrchestrationService;
import com.nexusagent.query.application.ToolOutputStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

@ExtendWith(MockitoExtension.class)
class AgentOrchestratorTest {

    @Mock
    QueryOrchestrationService queryOrchestrationService;

    @Mock
    ToolOutputStore toolOutputStore;

    @Mock
    AuditService auditService;

    AgentOrchestrator orchestrator;

    @BeforeEach
    void setUp() {
        orchestrator = new AgentOrchestrator(queryOrchestrationService, toolOutputStore, auditService);
        lenient().when(toolOutputStore.save(anyString(), anyString(), any())).thenReturn(Mono.empty());
        lenient().when(auditService.record(any(), any(), any(), any(), any(), any(), any())).thenReturn(Mono.empty());
        lenient().when(auditService.hashSensitiveValue(any())).thenReturn("question-hash");
    }

    @Test
    void directFallbackPlanUsesInsufficientContextFallback() {
        Plan plan = orchestrator.createPlan(new AgentQueryRequest("s1", "?", List.of(), 5, 1000, true));

        assertThat(plan.steps()).extracting(step -> step.action())
                .containsExactly(PlanAction.FALLBACK_INSUFFICIENT_CONTEXT);
    }

    @Test
    void retrievalNeededPlanUsesRetrieveThenGenerate() {
        Plan plan = orchestrator.createPlan(new AgentQueryRequest(
                "s1",
                "What does the security policy say?",
                List.of(),
                5,
                1000,
                true
        ));

        assertThat(plan.steps()).extracting(step -> step.action())
                .containsExactly(PlanAction.RETRIEVE_CONTEXT, PlanAction.GENERATE_ANSWER);
    }

    @Test
    void directLocalRequestSkipsRetrieval() {
        StepVerifier.create(orchestrator.query(new AgentQueryRequest("s1", "hello", List.of(), 5, 1000, true)))
                .assertNext(response -> {
                    assertThat(response.workflowStatus()).isEqualTo(AgentWorkflowStatus.COMPLETED);
                    assertThat(response.answer()).contains("deterministic MVP");
                    assertThat(response.citations()).isEmpty();
                    assertThat(response.executionResult().retrievalUsed()).isFalse();
                    assertThat(response.critiqueResult().outcome()).isEqualTo(CritiqueOutcome.PASS);
                })
                .verifyComplete();
    }

    @Test
    void executeRetrievalPathDelegatesToQueryPipeline() {
        AgentQueryRequest request = new AgentQueryRequest(
                "s1",
                "What does the security policy say?",
                List.of(),
                5,
                1000,
                true
        );
        QueryRequest expectedQueryRequest = new QueryRequest("s1", request.question(), List.of(), 5, 1000, true);
        when(queryOrchestrationService.execute(eq(expectedQueryRequest), anyString(), eq(RequestContext.defaults())))
                .thenAnswer(invocation -> Mono.just(queryResponse(invocation.getArgument(1), List.of(citation()))));

        StepVerifier.create(orchestrator.query(request))
                .assertNext(response -> {
                    assertThat(response.workflowStatus()).isEqualTo(AgentWorkflowStatus.COMPLETED);
                    assertThat(response.answer()).contains("[C1]");
                    assertThat(response.citations()).hasSize(1);
                    assertThat(response.executionResult().retrievalUsed()).isTrue();
                    assertThat(response.executionResult().retrievalCacheStatus()).isEqualTo("miss");
                    assertThat(response.critiqueResult().outcome()).isEqualTo(CritiqueOutcome.PASS);
                    assertThat(response.queryDebug()).isNotNull();
                })
                .verifyComplete();

        verify(queryOrchestrationService).execute(eq(expectedQueryRequest), anyString(), eq(RequestContext.defaults()));
    }

    @Test
    void critiquePassesWhenRetrievalAnswerMentionsCitation() {
        ExecutionResult executionResult = new ExecutionResult(
                "trace-1",
                List.of(PlanAction.RETRIEVE_CONTEXT, PlanAction.GENERATE_ANSWER),
                true,
                false,
                "query-orchestration-service",
                "miss",
                1,
                List.of()
        );
        Plan plan = orchestrator.createPlan(new AgentQueryRequest("s1", "security policy", List.of(), 5, 1000, true));

        assertThat(orchestrator.critique(plan, executionResult, "Answer uses [C1]", List.of(citation())).outcome())
                .isEqualTo(CritiqueOutcome.PASS);
    }

    @Test
    void critiqueFlagsMissingCitationsWhenRetrievalWasUsed() {
        ExecutionResult executionResult = new ExecutionResult(
                "trace-1",
                List.of(PlanAction.RETRIEVE_CONTEXT, PlanAction.GENERATE_ANSWER),
                true,
                false,
                "query-orchestration-service",
                "miss",
                0,
                List.of()
        );
        Plan plan = orchestrator.createPlan(new AgentQueryRequest("s1", "security policy", List.of(), 5, 1000, true));

        assertThat(orchestrator.critique(plan, executionResult, "Answer without sources", List.of()).outcome())
                .isEqualTo(CritiqueOutcome.MISSING_CITATIONS);
    }

    @Test
    void critiqueFlagsAnswerThatMentionsUnknownCitationMarker() {
        ExecutionResult executionResult = new ExecutionResult(
                "trace-1",
                List.of(PlanAction.RETRIEVE_CONTEXT, PlanAction.GENERATE_ANSWER),
                true,
                false,
                "query-orchestration-service",
                "miss",
                1,
                List.of()
        );
        Plan plan = orchestrator.createPlan(new AgentQueryRequest("s1", "security policy", List.of(), 5, 1000, true));

        assertThat(orchestrator.critique(plan, executionResult, "Answer cites [C2]", List.of(citation())).outcome())
                .isEqualTo(CritiqueOutcome.MISSING_CITATIONS);
    }

    @Test
    void lowInformationFallbackProducesFallbackCritique() {
        StepVerifier.create(orchestrator.query(new AgentQueryRequest("s1", "?", List.of(), 5, 1000, true)))
                .assertNext(response -> {
                    assertThat(response.workflowStatus()).isEqualTo(AgentWorkflowStatus.COMPLETED_WITH_FALLBACK);
                    assertThat(response.answer()).contains("insufficient question detail");
                    assertThat(response.citations()).isEmpty();
                    assertThat(response.executionResult().fallbackUsed()).isTrue();
                    assertThat(response.critiqueResult().outcome()).isEqualTo(CritiqueOutcome.FALLBACK_USED);
                })
                .verifyComplete();
    }

    @Test
    void insufficientContextFallbackIsMarkedByCritique() {
        AgentQueryRequest request = new AgentQueryRequest("s1", "security policy", List.of(), 5, 1000, true);
        QueryRequest expectedQueryRequest = new QueryRequest("s1", request.question(), List.of(), 5, 1000, true);
        when(queryOrchestrationService.execute(eq(expectedQueryRequest), anyString(), eq(RequestContext.defaults())))
                .thenAnswer(invocation -> Mono.just(new QueryResponse(
                        invocation.getArgument(1),
                        "LocalTemplateAnswerGenerator placeholder answer: insufficient retrieved context.",
                        List.of(),
                        null,
                        null,
                        null,
                        List.of("No retrieved context was available."),
                        "miss"
                )));

        StepVerifier.create(orchestrator.query(request))
                .assertNext(response -> {
                    assertThat(response.workflowStatus()).isEqualTo(AgentWorkflowStatus.COMPLETED_WITH_FALLBACK);
                    assertThat(response.critiqueResult().outcome()).isEqualTo(CritiqueOutcome.MISSING_CONTEXT);
                    assertThat(response.citations()).isEmpty();
                })
                .verifyComplete();
    }

    @Test
    void storesIntermediateWorkflowOutputs() {
        AgentQueryRequest request = new AgentQueryRequest("s1", "security policy", List.of(), 5, 1000, true);
        QueryRequest expectedQueryRequest = new QueryRequest("s1", request.question(), List.of(), 5, 1000, true);
        when(queryOrchestrationService.execute(eq(expectedQueryRequest), anyString(), eq(RequestContext.defaults())))
                .thenAnswer(invocation -> Mono.just(queryResponse(invocation.getArgument(1), List.of(citation()))));

        StepVerifier.create(orchestrator.query(request))
                .assertNext(response -> assertThat(response.workflowStatus()).isEqualTo(AgentWorkflowStatus.COMPLETED))
                .verifyComplete();

        verify(toolOutputStore).save(eq("s1"), eq("agent-plan"), any());
        verify(toolOutputStore).save(eq("s1"), eq("agent-execution"), any());
        verify(toolOutputStore).save(eq("s1"), eq("agent-critique"), any());
    }

    @Test
    void debugFalseHidesWorkflowInternals() {
        AgentQueryRequest request = new AgentQueryRequest("s1", "security policy", List.of(), 5, 1000, false);
        QueryRequest expectedQueryRequest = new QueryRequest("s1", request.question(), List.of(), 5, 1000, true);
        when(queryOrchestrationService.execute(eq(expectedQueryRequest), anyString(), eq(RequestContext.defaults())))
                .thenAnswer(invocation -> Mono.just(queryResponse(invocation.getArgument(1), List.of(citation()))));

        StepVerifier.create(orchestrator.query(request))
                .assertNext(response -> {
                    assertThat(response.workflowStatus()).isEqualTo(AgentWorkflowStatus.COMPLETED);
                    assertThat(response.plan()).isNull();
                    assertThat(response.executionResult()).isNull();
                    assertThat(response.critiqueResult()).isNull();
                    assertThat(response.queryDebug()).isNull();
                })
                .verifyComplete();
    }

    @Test
    void debugTrueExposesWorkflowInternals() {
        AgentQueryRequest request = new AgentQueryRequest("s1", "security policy", List.of(), 5, 1000, true);
        QueryRequest expectedQueryRequest = new QueryRequest("s1", request.question(), List.of(), 5, 1000, true);
        when(queryOrchestrationService.execute(eq(expectedQueryRequest), anyString(), eq(RequestContext.defaults())))
                .thenAnswer(invocation -> Mono.just(queryResponse(invocation.getArgument(1), List.of(citation()))));

        StepVerifier.create(orchestrator.query(request))
                .assertNext(response -> {
                    assertThat(response.plan()).isNotNull();
                    assertThat(response.executionResult()).isNotNull();
                    assertThat(response.critiqueResult()).isNotNull();
                    assertThat(response.queryDebug()).isNotNull();
                })
                .verifyComplete();
    }

    private QueryResponse queryResponse(String traceId, List<Citation> citations) {
        return new QueryResponse(
                traceId,
                "LocalTemplateAnswerGenerator placeholder answer: Based on retrieved evidence [C1].",
                citations,
                "[C1] policy.txt\nSecurity policy access controls.",
                null,
                null,
                List.of("local placeholder"),
                "miss"
        );
    }

    private Citation citation() {
        return new Citation(
                1,
                "[C1]",
                UUID.randomUUID(),
                "policy.txt",
                UUID.randomUUID(),
                UUID.randomUUID(),
                0,
                null,
                0,
                20,
                "security policy"
        );
    }
}
