package com.nexusagent.retrieval.api;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.UUID;

import com.nexusagent.common.context.RequestContext;
import com.nexusagent.common.error.GlobalExceptionHandler;
import com.nexusagent.retrieval.application.HybridRetrievalService;
import com.nexusagent.retrieval.domain.FusedRetrievalCandidate;
import com.nexusagent.retrieval.domain.HybridRetrievalResult;
import com.nexusagent.retrieval.domain.RetrievalSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.reactive.WebFluxTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Mono;

@WebFluxTest(controllers = RetrievalDebugController.class)
@Import(GlobalExceptionHandler.class)
class RetrievalDebugControllerRouteTest {

    @Autowired
    WebTestClient webTestClient;

    @MockBean
    HybridRetrievalService hybridRetrievalService;

    @Test
    void postDebugRouteHitsController() {
        UUID documentId = UUID.randomUUID();
        UUID parentId = UUID.randomUUID();
        UUID childId = UUID.randomUUID();
        HybridRetrievalResult result = new HybridRetrievalResult(
                "security policy",
                List.of(),
                List.of(),
                List.of(new FusedRetrievalCandidate(
                        childId,
                        documentId,
                        parentId,
                        3,
                        "security policy preview",
                        RetrievalSource.BOTH,
                        1,
                        0.12d,
                        2,
                        0.83d,
                        0.0325d
                ))
        );
        RequestContext context = RequestContext.defaults();
        when(hybridRetrievalService.retrieve(eq("security policy"), eq(List.of(documentId)), eq(5), eq(context)))
                .thenReturn(Mono.just(result));

        webTestClient.post()
                .uri("/api/v1/retrieval/debug")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("""
                        {
                          "query": "security policy",
                          "documentIds": ["%s"],
                          "topK": 5
                        }
                        """.formatted(documentId))
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.query").isEqualTo("security policy")
                .jsonPath("$.fusedCandidates[0].childChunkId").isEqualTo(childId.toString())
                .jsonPath("$.fusedCandidates[0].parentChunkId").isEqualTo(parentId.toString())
                .jsonPath("$.fusedCandidates[0].documentId").isEqualTo(documentId.toString())
                .jsonPath("$.fusedCandidates[0].source").isEqualTo("both")
                .jsonPath("$.fusedCandidates[0].vectorRank").isEqualTo(1)
                .jsonPath("$.fusedCandidates[0].fullTextRank").isEqualTo(2);

        verify(hybridRetrievalService).retrieve("security policy", List.of(documentId), 5, context);
    }
}
