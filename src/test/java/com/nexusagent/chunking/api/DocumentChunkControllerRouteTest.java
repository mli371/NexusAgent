package com.nexusagent.chunking.api;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.UUID;

import com.nexusagent.chunking.application.DocumentChunkingService;
import com.nexusagent.chunking.domain.ChunkedDocument;
import com.nexusagent.common.context.RequestContext;
import com.nexusagent.common.error.GlobalExceptionHandler;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.reactive.WebFluxTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Mono;

@WebFluxTest(controllers = DocumentChunkController.class)
@Import(GlobalExceptionHandler.class)
class DocumentChunkControllerRouteTest {

    @Autowired
    WebTestClient webTestClient;

    @MockBean
    DocumentChunkingService documentChunkingService;

    @Test
    void postChunksRouteHitsController() {
        UUID documentId = UUID.randomUUID();
        RequestContext context = RequestContext.defaults();
        when(documentChunkingService.extractAndChunk(documentId, context, false))
                .thenReturn(Mono.just(new ChunkedDocument(documentId, List.of(), List.of())));

        webTestClient.post()
                .uri("/api/v1/documents/{documentId}/chunks", documentId)
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.documentId").isEqualTo(documentId.toString())
                .jsonPath("$.parentChunkCount").isEqualTo(0)
                .jsonPath("$.childChunkCount").isEqualTo(0);

        verify(documentChunkingService).extractAndChunk(documentId, context, false);
    }

    @Test
    void postChunksForceRoutePassesForceTrue() {
        UUID documentId = UUID.randomUUID();
        RequestContext context = RequestContext.defaults();
        when(documentChunkingService.extractAndChunk(documentId, context, true))
                .thenReturn(Mono.just(new ChunkedDocument(documentId, List.of(), List.of())));

        webTestClient.post()
                .uri("/api/v1/documents/{documentId}/chunks?force=true", documentId)
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.documentId").isEqualTo(documentId.toString());

        verify(documentChunkingService).extractAndChunk(documentId, context, true);
    }

    @Test
    void getChunksRouteHitsController() {
        UUID documentId = UUID.randomUUID();
        RequestContext context = RequestContext.defaults();
        when(documentChunkingService.getChunks(documentId, context))
                .thenReturn(Mono.just(new ChunkedDocument(documentId, List.of(), List.of())));

        webTestClient.get()
                .uri("/api/v1/documents/{documentId}/chunks", documentId)
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.documentId").isEqualTo(documentId.toString())
                .jsonPath("$.parentChunkCount").isEqualTo(0)
                .jsonPath("$.childChunkCount").isEqualTo(0);

        verify(documentChunkingService).getChunks(documentId, context);
    }

    @Test
    void missingRouteReturnsNotFound() {
        webTestClient.post()
                .uri("/api/v1/documents/not-a-real-route/chunks/extra")
                .exchange()
                .expectStatus().isNotFound()
                .expectBody()
                .jsonPath("$.status").isEqualTo(404)
                .jsonPath("$.message").isEqualTo("Route not found");
    }
}
