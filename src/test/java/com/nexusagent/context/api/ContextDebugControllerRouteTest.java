package com.nexusagent.context.api;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.UUID;

import com.nexusagent.common.context.RequestContext;
import com.nexusagent.common.error.GlobalExceptionHandler;
import com.nexusagent.context.application.ContextBuilder;
import com.nexusagent.context.domain.Citation;
import com.nexusagent.context.domain.ContextBuildResult;
import com.nexusagent.context.domain.ContextDebugMetadata;
import com.nexusagent.context.domain.ExpandedParentContext;
import com.nexusagent.context.domain.SelectedChildChunk;
import com.nexusagent.retrieval.domain.RetrievalSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.reactive.WebFluxTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Mono;

@WebFluxTest(controllers = ContextDebugController.class)
@Import(GlobalExceptionHandler.class)
class ContextDebugControllerRouteTest {

    @Autowired
    WebTestClient webTestClient;

    @MockBean
    ContextBuilder contextBuilder;

    @Test
    void postContextDebugRouteHitsController() {
        UUID documentId = UUID.randomUUID();
        UUID parentId = UUID.randomUUID();
        UUID childId = UUID.randomUUID();
        ContextBuildResult result = new ContextBuildResult(
                "security policy",
                List.of(),
                List.of(new SelectedChildChunk(
                        childId,
                        parentId,
                        documentId,
                        "notes.txt",
                        0,
                        0,
                        15,
                        "security policy",
                        RetrievalSource.BOTH,
                        1,
                        3.1d,
                        "Selected for citation [C1]"
                )),
                List.of(new ExpandedParentContext(
                        parentId,
                        documentId,
                        "notes.txt",
                        0,
                        0,
                        30,
                        "Security policy parent context",
                        false,
                        30,
                        List.of(childId)
                )),
                List.of(new Citation(
                        1,
                        "[C1]",
                        documentId,
                        "notes.txt",
                        parentId,
                        childId,
                        0,
                        null,
                        0,
                        15,
                        "security policy"
                )),
                "[C1] notes.txt\nSecurity policy parent context",
                new ContextDebugMetadata("deterministic-heuristic", 1, 1, 1, 1, 1000, 30, 0, 0)
        );
        RequestContext context = RequestContext.defaults();
        when(contextBuilder.build(eq("security policy"), eq(List.of(documentId)), eq(5), eq(1000), eq(context)))
                .thenReturn(Mono.just(result));

        webTestClient.post()
                .uri("/api/v1/context/debug")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("""
                        {
                          "query": "security policy",
                          "documentIds": ["%s"],
                          "topK": 5,
                          "contextBudgetChars": 1000
                        }
                        """.formatted(documentId))
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.query").isEqualTo("security policy")
                .jsonPath("$.rerankedCandidates").isArray()
                .jsonPath("$.selectedChildChunks").isArray()
                .jsonPath("$.expandedParentContexts").isArray()
                .jsonPath("$.citations").isArray()
                .jsonPath("$.finalContextText").isEqualTo("[C1] notes.txt\nSecurity policy parent context")
                .jsonPath("$.selectedChildChunks[0].childChunkId").isEqualTo(childId.toString())
                .jsonPath("$.expandedParentContexts[0].parentChunkId").isEqualTo(parentId.toString())
                .jsonPath("$.citations[0].citationMarker").isEqualTo("[C1]")
                .jsonPath("$.debugMetadata.reranker").isEqualTo("deterministic-heuristic");

        verify(contextBuilder).build("security policy", List.of(documentId), 5, 1000, context);
    }
}
