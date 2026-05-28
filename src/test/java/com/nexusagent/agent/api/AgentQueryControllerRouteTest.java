package com.nexusagent.agent.api;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.UUID;

import com.nexusagent.agent.application.AgentOrchestrator;
import com.nexusagent.agent.domain.AgentWorkflowStatus;
import com.nexusagent.agent.domain.CritiqueOutcome;
import com.nexusagent.agent.domain.CritiqueResult;
import com.nexusagent.agent.domain.ExecutionResult;
import com.nexusagent.agent.domain.Plan;
import com.nexusagent.agent.domain.PlanAction;
import com.nexusagent.agent.domain.PlanStep;
import com.nexusagent.common.context.RequestContext;
import com.nexusagent.common.error.BadRequestException;
import com.nexusagent.common.error.GlobalExceptionHandler;
import com.nexusagent.context.domain.Citation;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.reactive.WebFluxTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Mono;

@WebFluxTest(
        controllers = AgentQueryController.class,
        properties = "spring.autoconfigure.exclude=org.springframework.boot.autoconfigure.data.redis.RedisReactiveAutoConfiguration,org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration"
)
@Import(GlobalExceptionHandler.class)
class AgentQueryControllerRouteTest {

    @Autowired
    WebTestClient webTestClient;

    @MockBean
    AgentOrchestrator agentOrchestrator;

    @Test
    void postAgentQueryRouteReturnsPublicResponse() {
        UUID documentId = UUID.randomUUID();
        AgentQueryRequest request = new AgentQueryRequest("s1", "security policy", List.of(documentId), 5, 1000, false);
        AgentQueryResponse response = new AgentQueryResponse(
                "trace-1",
                "answer [C1]",
                List.of(citation(documentId)),
                AgentWorkflowStatus.COMPLETED,
                null,
                null,
                null,
                null
        );
        RequestContext context = RequestContext.defaults();
        when(agentOrchestrator.query(request, context, null)).thenReturn(Mono.just(response));

        webTestClient.post()
                .uri("/api/v1/agent/query")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("""
                        {
                          "sessionId": "s1",
                          "question": "security policy",
                          "documentIds": ["%s"],
                          "topK": 5,
                          "contextBudgetChars": 1000,
                          "debug": false
                        }
                        """.formatted(documentId))
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.traceId").isEqualTo("trace-1")
                .jsonPath("$.answer").isEqualTo("answer [C1]")
                .jsonPath("$.workflowStatus").isEqualTo("COMPLETED")
                .jsonPath("$.citations[0].documentId").isEqualTo(documentId.toString())
                .jsonPath("$.plan").doesNotExist()
                .jsonPath("$.executionResult").doesNotExist()
                .jsonPath("$.critiqueResult").doesNotExist()
                .jsonPath("$.queryDebug").doesNotExist();

        verify(agentOrchestrator).query(request, context, null);
    }

    @Test
    void postAgentQueryRouteReturnsDebugResponse() {
        AgentQueryRequest request = new AgentQueryRequest("s1", "security policy", List.of(), 5, 1000, true);
        AgentQueryResponse response = new AgentQueryResponse(
                "trace-1",
                "answer [C1]",
                List.of(citation(UUID.randomUUID())),
                AgentWorkflowStatus.COMPLETED,
                new Plan(
                        "deterministic-rule-planner",
                        "retrieve",
                        List.of(new PlanStep(1, PlanAction.RETRIEVE_CONTEXT, "retrieve context"))
                ),
                new ExecutionResult(
                        "trace-1",
                        List.of(PlanAction.RETRIEVE_CONTEXT, PlanAction.GENERATE_ANSWER),
                        true,
                        false,
                        "query-orchestration-service",
                        "miss",
                        1,
                        List.of("executed")
                ),
                new CritiqueResult(CritiqueOutcome.PASS, true, List.of("grounded")),
                null
        );
        RequestContext context = RequestContext.defaults();
        when(agentOrchestrator.query(request, context, null)).thenReturn(Mono.just(response));

        webTestClient.post()
                .uri("/api/v1/agent/query")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("""
                        {
                          "sessionId": "s1",
                          "question": "security policy",
                          "documentIds": [],
                          "topK": 5,
                          "contextBudgetChars": 1000,
                          "debug": true
                        }
                        """)
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.plan.plannerName").isEqualTo("deterministic-rule-planner")
                .jsonPath("$.executionResult.retrievalUsed").isEqualTo(true)
                .jsonPath("$.executionResult.retrievalCacheStatus").isEqualTo("miss")
                .jsonPath("$.critiqueResult.outcome").isEqualTo("PASS");
    }

    @Test
    void postAgentQueryReturnsBadRequestForBlankQuestion() {
        AgentQueryRequest request = new AgentQueryRequest("s1", "  ", List.of(), 5, 1000, false);
        RequestContext context = RequestContext.defaults();
        when(agentOrchestrator.query(request, context, null))
                .thenReturn(Mono.error(new BadRequestException("question must not be blank")));

        webTestClient.post()
                .uri("/api/v1/agent/query")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("""
                        {
                          "sessionId": "s1",
                          "question": "  ",
                          "documentIds": [],
                          "topK": 5,
                          "contextBudgetChars": 1000,
                          "debug": false
                        }
                        """)
                .exchange()
                .expectStatus().isBadRequest()
                .expectBody()
                .jsonPath("$.status").isEqualTo(400)
                .jsonPath("$.message").isEqualTo("question must not be blank");
    }

    private Citation citation(UUID documentId) {
        return new Citation(
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
        );
    }
}
