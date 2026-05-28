package com.nexusagent.embeddings.api;

import java.util.UUID;

import com.nexusagent.common.context.RequestContext;
import com.nexusagent.embeddings.application.ChildChunkEmbeddingService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestHeader;
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
    public Mono<EmbeddingStatusResponse> embed(
            @PathVariable UUID documentId,
            @RequestHeader(name = RequestContext.TENANT_HEADER, required = false) String tenantId,
            @RequestHeader(name = RequestContext.ACTOR_HEADER, required = false) String actorId
    ) {
        return childChunkEmbeddingService.embedDocument(documentId, RequestContext.fromHeaders(tenantId, actorId))
                .map(EmbeddingStatusResponse::from);
    }

    @GetMapping("/embedding-status")
    public Mono<EmbeddingStatusResponse> embeddingStatus(
            @PathVariable UUID documentId,
            @RequestHeader(name = RequestContext.TENANT_HEADER, required = false) String tenantId,
            @RequestHeader(name = RequestContext.ACTOR_HEADER, required = false) String actorId
    ) {
        return childChunkEmbeddingService.getStatus(documentId, RequestContext.fromHeaders(tenantId, actorId))
                .map(EmbeddingStatusResponse::from);
    }
}
