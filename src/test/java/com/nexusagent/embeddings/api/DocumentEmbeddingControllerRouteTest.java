package com.nexusagent.embeddings.api;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.UUID;

import com.nexusagent.common.context.RequestContext;
import com.nexusagent.common.error.GlobalExceptionHandler;
import com.nexusagent.embeddings.application.ChildChunkEmbeddingService;
import com.nexusagent.embeddings.domain.EmbeddingStatus;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.reactive.WebFluxTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Mono;

@WebFluxTest(controllers = DocumentEmbeddingController.class)
@Import(GlobalExceptionHandler.class)
class DocumentEmbeddingControllerRouteTest {

    @Autowired
    WebTestClient webTestClient;

    @MockBean
    ChildChunkEmbeddingService childChunkEmbeddingService;

    @Test
    void postEmbedRouteHitsController() {
        UUID documentId = UUID.randomUUID();
        RequestContext context = RequestContext.defaults();
        when(childChunkEmbeddingService.embedDocument(documentId, context)).thenReturn(Mono.just(status(documentId)));

        webTestClient.post()
                .uri("/api/v1/documents/{documentId}/embed", documentId)
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.documentId").isEqualTo(documentId.toString())
                .jsonPath("$.childChunkCount").isEqualTo(2)
                .jsonPath("$.embeddedChildChunkCount").isEqualTo(2)
                .jsonPath("$.complete").isEqualTo(true);

        verify(childChunkEmbeddingService).embedDocument(documentId, context);
    }

    @Test
    void getEmbeddingStatusRouteHitsController() {
        UUID documentId = UUID.randomUUID();
        RequestContext context = RequestContext.defaults();
        when(childChunkEmbeddingService.getStatus(documentId, context)).thenReturn(Mono.just(status(documentId)));

        webTestClient.get()
                .uri("/api/v1/documents/{documentId}/embedding-status", documentId)
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.documentId").isEqualTo(documentId.toString())
                .jsonPath("$.missingChildChunkCount").isEqualTo(0)
                .jsonPath("$.provider").isEqualTo("local");

        verify(childChunkEmbeddingService).getStatus(documentId, context);
    }

    private EmbeddingStatus status(UUID documentId) {
        return new EmbeddingStatus(
                documentId,
                2,
                2,
                0,
                true,
                "local",
                "local-deterministic-hash-384",
                384
        );
    }
}
