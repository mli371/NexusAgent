package com.nexusagent.agent.api;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.UUID;

import com.nexusagent.agent.application.AgentOrchestrator;
import com.nexusagent.common.context.RequestContext;
import com.nexusagent.common.error.GlobalExceptionHandler;
import com.nexusagent.context.domain.Citation;
import com.nexusagent.enterprise.audit.AuditService;
import com.nexusagent.query.api.QueryRequest;
import com.nexusagent.query.api.QueryResponse;
import com.nexusagent.query.application.QueryOrchestrationService;
import com.nexusagent.query.application.ToolOutputStore;
import org.junit.jupiter.api.BeforeEach;
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
@Import({GlobalExceptionHandler.class, AgentOrchestrator.class})
class AgentQueryControllerWorkflowTest {

    @Autowired
    WebTestClient webTestClient;

    @MockBean
    QueryOrchestrationService queryOrchestrationService;

    @MockBean
    ToolOutputStore toolOutputStore;

    @MockBean
    AuditService auditService;

    @BeforeEach
    void setUp() {
        when(toolOutputStore.save(anyString(), anyString(), org.mockito.ArgumentMatchers.any()))
                .thenReturn(Mono.empty());
        when(auditService.record(
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any()
        )).thenReturn(Mono.empty());
        when(auditService.hashSensitiveValue(org.mockito.ArgumentMatchers.any())).thenReturn("question-hash");
    }

    @Test
    void postAgentQueryExecutesRetrievalBackedDocumentQuestion() {
        QueryRequest expectedQueryRequest = new QueryRequest("s1", "What does the policy say?", List.of(), 5, 1000, true);
        when(queryOrchestrationService.execute(eq(expectedQueryRequest), anyString(), eq(RequestContext.defaults())))
                .thenAnswer(invocation -> Mono.just(queryResponse(invocation.getArgument(1), List.of(citation()))));

        webTestClient.post()
                .uri("/api/v1/agent/query")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("""
                        {
                          "sessionId": "s1",
                          "question": "What does the policy say?",
                          "documentIds": [],
                          "topK": 5,
                          "contextBudgetChars": 1000,
                          "debug": true
                        }
                        """)
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.workflowStatus").isEqualTo("COMPLETED")
                .jsonPath("$.answer").value(value -> value.toString().contains("[C1]"))
                .jsonPath("$.plan.steps[0].action").isEqualTo("RETRIEVE_CONTEXT")
                .jsonPath("$.executionResult.retrievalUsed").isEqualTo(true)
                .jsonPath("$.critiqueResult.outcome").isEqualTo("PASS")
                .jsonPath("$.queryDebug.traceId").exists();
    }

    @Test
    void postAgentQueryExecutesDirectGreeting() {
        webTestClient.post()
                .uri("/api/v1/agent/query")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("""
                        {
                          "sessionId": "s1",
                          "question": "hello",
                          "documentIds": [],
                          "topK": 5,
                          "contextBudgetChars": 1000,
                          "debug": true
                        }
                        """)
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.workflowStatus").isEqualTo("COMPLETED")
                .jsonPath("$.plan.steps[0].action").isEqualTo("GENERATE_ANSWER")
                .jsonPath("$.executionResult.retrievalUsed").isEqualTo(false)
                .jsonPath("$.critiqueResult.outcome").isEqualTo("PASS")
                .jsonPath("$.queryDebug").doesNotExist();
    }

    @Test
    void postAgentQueryExecutesLowInformationFallback() {
        webTestClient.post()
                .uri("/api/v1/agent/query")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("""
                        {
                          "sessionId": "s1",
                          "question": "?",
                          "documentIds": [],
                          "topK": 5,
                          "contextBudgetChars": 1000,
                          "debug": true
                        }
                        """)
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.workflowStatus").isEqualTo("COMPLETED_WITH_FALLBACK")
                .jsonPath("$.plan.steps[0].action").isEqualTo("FALLBACK_INSUFFICIENT_CONTEXT")
                .jsonPath("$.executionResult.fallbackUsed").isEqualTo(true)
                .jsonPath("$.critiqueResult.outcome").isEqualTo("FALLBACK_USED")
                .jsonPath("$.queryDebug").doesNotExist();
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
