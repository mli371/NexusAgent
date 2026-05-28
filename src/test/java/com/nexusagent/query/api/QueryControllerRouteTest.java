package com.nexusagent.query.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.UUID;

import com.nexusagent.common.context.RequestContext;
import com.nexusagent.common.error.BadRequestException;
import com.nexusagent.common.error.GlobalExceptionHandler;
import com.nexusagent.context.api.ContextDebugResponse;
import com.nexusagent.context.domain.Citation;
import com.nexusagent.context.domain.ContextDebugMetadata;
import com.nexusagent.query.application.QueryOrchestrationService;
import com.nexusagent.retrieval.api.RetrievalDebugResponse;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.reactive.WebFluxTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@WebFluxTest(
        controllers = QueryController.class,
        properties = "spring.autoconfigure.exclude=org.springframework.boot.autoconfigure.data.redis.RedisReactiveAutoConfiguration,org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration"
)
@Import(GlobalExceptionHandler.class)
class QueryControllerRouteTest {

    @Autowired
    WebTestClient webTestClient;

    @MockBean
    QueryOrchestrationService queryOrchestrationService;

    @Test
    void postQueryRouteReturnsQueryResponse() {
        UUID documentId = UUID.randomUUID();
        QueryRequest request = new QueryRequest("s1", "security policy", List.of(documentId), 5, 1000, false);
        QueryResponse response = new QueryResponse(
                "trace-1",
                "LocalTemplateAnswerGenerator placeholder answer",
                List.of(citation(documentId)),
                null,
                null,
                null,
                null,
                null
        );
        RequestContext context = RequestContext.defaults();
        when(queryOrchestrationService.execute(eq(request), isNull(), eq(context))).thenReturn(Mono.just(response));

        webTestClient.post()
                .uri("/api/v1/query")
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
                .jsonPath("$.answer").value(value -> value.toString().contains("placeholder"))
                .jsonPath("$.citations[0].documentId").isEqualTo(documentId.toString())
                .jsonPath("$.finalContextText").doesNotExist()
                .jsonPath("$.retrievalDebug").doesNotExist()
                .jsonPath("$.contextDebug").doesNotExist()
                .jsonPath("$.limitations").doesNotExist()
                .jsonPath("$.retrievalCacheStatus").doesNotExist();

        verify(queryOrchestrationService).execute(request, null, context);
    }

    @Test
    void postQueryRouteReturnsDebugFieldsWhenDebugTrue() {
        UUID documentId = UUID.randomUUID();
        QueryRequest request = new QueryRequest("s1", "security policy", List.of(documentId), 5, 1000, true);
        QueryResponse response = new QueryResponse(
                "trace-1",
                "LocalTemplateAnswerGenerator placeholder answer",
                List.of(citation(documentId)),
                "[C1] policy.txt\nSecurity policy access controls.",
                new RetrievalDebugResponse("security policy", List.of(), List.of(), List.of()),
                new ContextDebugResponse(
                        "security policy",
                        List.of(),
                        List.of(),
                        List.of(),
                        List.of(),
                        "[C1] policy.txt\nSecurity policy access controls.",
                        new ContextDebugMetadata("deterministic-heuristic", 0, 0, 0, 0, 1000, 0, 0, 0)
                ),
                List.of("local placeholder"),
                "miss"
        );
        RequestContext context = RequestContext.defaults();
        when(queryOrchestrationService.execute(eq(request), isNull(), eq(context))).thenReturn(Mono.just(response));

        webTestClient.post()
                .uri("/api/v1/query")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("""
                        {
                          "sessionId": "s1",
                          "question": "security policy",
                          "documentIds": ["%s"],
                          "topK": 5,
                          "contextBudgetChars": 1000,
                          "debug": true
                        }
                        """.formatted(documentId))
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.finalContextText").value(value -> assertThat(value.toString()).contains("Security policy"))
                .jsonPath("$.retrievalDebug.query").isEqualTo("security policy")
                .jsonPath("$.contextDebug.query").isEqualTo("security policy")
                .jsonPath("$.limitations[0]").isEqualTo("local placeholder")
                .jsonPath("$.retrievalCacheStatus").isEqualTo("miss");

        verify(queryOrchestrationService).execute(request, null, context);
    }

    @Test
    void postQueryStreamRouteReturnsSseContentTypeAndEvents() {
        QueryRequest request = new QueryRequest("s1", "security policy", List.of(), 5, 1000, false);
        RequestContext context = RequestContext.defaults();
        when(queryOrchestrationService.stream(eq(request), isNull(), eq(context))).thenReturn(Flux.just(
                event("received", "trace-1", "query received"),
                event("retrieving", "trace-1", "building retrieval candidates"),
                event("reranking", "trace-1", "reranking fused candidates"),
                event("building_context", "trace-1", "expanding parent context"),
                event("generating", "trace-1", "generating local placeholder answer"),
                event("message", "trace-1", "LocalTemplateAnswerGenerator placeholder answer"),
                event("completed", "trace-1", "query completed")
        ));

        webTestClient.post()
                .uri("/api/v1/query/stream")
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.TEXT_EVENT_STREAM)
                .bodyValue("""
                        {
                          "sessionId": "s1",
                          "question": "security policy",
                          "documentIds": [],
                          "topK": 5,
                          "contextBudgetChars": 1000,
                          "debug": false
                        }
                        """)
                .exchange()
                .expectStatus().isOk()
                .expectHeader().contentTypeCompatibleWith(MediaType.TEXT_EVENT_STREAM)
                .expectBody(String.class)
                .value(body -> {
                    assertThat(body).contains("event:received");
                    assertThat(body).contains("event:retrieving");
                    assertThat(body).contains("event:reranking");
                    assertThat(body).contains("event:building_context");
                    assertThat(body).contains("event:generating");
                    assertThat(body).contains("event:message");
                    assertThat(body).contains("event:completed");
                    assertThat(body).contains("trace-1");
                });

        verify(queryOrchestrationService).stream(request, null, context);
    }

    @Test
    void postQueryReturnsBadRequestForInvalidTopK() {
        QueryRequest request = new QueryRequest("s1", "security policy", List.of(), 0, 1000, false);
        RequestContext context = RequestContext.defaults();
        when(queryOrchestrationService.execute(eq(request), isNull(), eq(context)))
                .thenReturn(Mono.error(new BadRequestException("topK must be greater than 0")));

        webTestClient.post()
                .uri("/api/v1/query")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("""
                        {
                          "sessionId": "s1",
                          "question": "security policy",
                          "documentIds": [],
                          "topK": 0,
                          "contextBudgetChars": 1000,
                          "debug": false
                        }
                        """)
                .exchange()
                .expectStatus().isBadRequest()
                .expectBody()
                .jsonPath("$.status").isEqualTo(400)
                .jsonPath("$.message").isEqualTo("topK must be greater than 0");
    }

    @Test
    void postQueryReturnsBadRequestForBlankQuestion() {
        QueryRequest request = new QueryRequest("s1", "  ", List.of(), 5, 1000, false);
        RequestContext context = RequestContext.defaults();
        when(queryOrchestrationService.execute(eq(request), isNull(), eq(context)))
                .thenReturn(Mono.error(new BadRequestException("question must not be blank")));

        webTestClient.post()
                .uri("/api/v1/query")
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

    @Test
    void postQueryReturnsBadRequestForInvalidContextBudget() {
        QueryRequest request = new QueryRequest("s1", "security policy", List.of(), 5, 0, false);
        RequestContext context = RequestContext.defaults();
        when(queryOrchestrationService.execute(eq(request), isNull(), eq(context)))
                .thenReturn(Mono.error(new BadRequestException("contextBudgetChars must be greater than 0")));

        webTestClient.post()
                .uri("/api/v1/query")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("""
                        {
                          "sessionId": "s1",
                          "question": "security policy",
                          "documentIds": [],
                          "topK": 5,
                          "contextBudgetChars": 0,
                          "debug": false
                        }
                        """)
                .exchange()
                .expectStatus().isBadRequest()
                .expectBody()
                .jsonPath("$.status").isEqualTo(400)
                .jsonPath("$.message").isEqualTo("contextBudgetChars must be greater than 0");
    }

    @Test
    void postQueryPropagatesTenantActorAndTraceHeaders() {
        QueryRequest request = new QueryRequest("s1", "security policy", List.of(), 5, 1000, false);
        RequestContext context = new RequestContext("tenant-a", "actor-1");
        QueryResponse response = new QueryResponse(
                "trace-from-header",
                "answer",
                List.of(),
                null,
                null,
                null,
                null,
                null
        );
        when(queryOrchestrationService.execute(eq(request), eq("trace-from-header"), eq(context)))
                .thenReturn(Mono.just(response));

        webTestClient.post()
                .uri("/api/v1/query")
                .header(RequestContext.TENANT_HEADER, "tenant-a")
                .header(RequestContext.ACTOR_HEADER, "actor-1")
                .header(RequestContext.TRACE_HEADER, "trace-from-header")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("""
                        {
                          "sessionId": "s1",
                          "question": "security policy",
                          "documentIds": [],
                          "topK": 5,
                          "contextBudgetChars": 1000,
                          "debug": false
                        }
                        """)
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.traceId").isEqualTo("trace-from-header");

        verify(queryOrchestrationService).execute(request, "trace-from-header", context);
    }

    private ServerSentEvent<QueryStreamEvent> event(String eventName, String traceId, String message) {
        return ServerSentEvent.<QueryStreamEvent>builder()
                .event(eventName)
                .data(new QueryStreamEvent(eventName, traceId, message, null))
                .build();
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
