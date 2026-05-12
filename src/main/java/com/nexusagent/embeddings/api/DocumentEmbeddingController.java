package com.nexusagent.embeddings.api;

import java.util.UUID;

import com.nexusagent.embeddings.application.ChildChunkEmbeddingService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

@RestController
@RequestMapping("/api/v1/documents/{documentId}")
public class DocumentEmbeddingController {

    private final ChildChunkEmbeddingService childChunkEmbeddingService;

    public DocumentEmbeddingController(ChildChunkEmbeddingService childChunkEmbeddingService) {
        this.childChunkEmbeddingService = childChunkEmbeddingService;
    }

    @PostMapping("/embed")
    public Mono<EmbeddingStatusResponse> embed(@PathVariable UUID documentId) {
        return childChunkEmbeddingService.embedDocument(documentId)
                .map(EmbeddingStatusResponse::from);
    }

    @GetMapping("/embedding-status")
    public Mono<EmbeddingStatusResponse> embeddingStatus(@PathVariable UUID documentId) {
        return childChunkEmbeddingService.getStatus(documentId)
                .map(EmbeddingStatusResponse::from);
    }
}
