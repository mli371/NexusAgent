package com.nexusagent.chunking.api;

import java.util.UUID;

import com.nexusagent.chunking.application.DocumentChunkingService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
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
            @RequestParam(defaultValue = "false") boolean force
    ) {
        return documentChunkingService.extractAndChunk(documentId, force)
                .map(ChunkedDocumentResponse::from);
    }

    @GetMapping
    public Mono<ChunkedDocumentResponse> getChunks(@PathVariable UUID documentId) {
        return documentChunkingService.getChunks(documentId)
                .map(ChunkedDocumentResponse::from);
    }
}
