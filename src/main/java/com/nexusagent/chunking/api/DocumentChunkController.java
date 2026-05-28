package com.nexusagent.chunking.api;

import java.util.UUID;

import com.nexusagent.chunking.application.DocumentChunkingService;
import com.nexusagent.common.context.RequestContext;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

@RestController
@RequestMapping("/api/v1/documents/{documentId}/chunks")
public class DocumentChunkController {

    private final DocumentChunkingService documentChunkingService;

    public DocumentChunkController(DocumentChunkingService documentChunkingService) {
        this.documentChunkingService = documentChunkingService;
    }

    @PostMapping
    public Mono<ChunkedDocumentResponse> extractAndChunk(
            @PathVariable UUID documentId,
            @RequestParam(defaultValue = "false") boolean force,
            @RequestHeader(name = RequestContext.TENANT_HEADER, required = false) String tenantId,
            @RequestHeader(name = RequestContext.ACTOR_HEADER, required = false) String actorId
    ) {
        return documentChunkingService.extractAndChunk(
                        documentId,
                        RequestContext.fromHeaders(tenantId, actorId),
                        force
                )
                .map(ChunkedDocumentResponse::from);
    }

    @GetMapping
    public Mono<ChunkedDocumentResponse> getChunks(
            @PathVariable UUID documentId,
            @RequestHeader(name = RequestContext.TENANT_HEADER, required = false) String tenantId,
            @RequestHeader(name = RequestContext.ACTOR_HEADER, required = false) String actorId
    ) {
        return documentChunkingService.getChunks(documentId, RequestContext.fromHeaders(tenantId, actorId))
                .map(ChunkedDocumentResponse::from);
    }
}
