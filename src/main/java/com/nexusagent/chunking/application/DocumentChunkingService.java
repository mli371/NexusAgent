package com.nexusagent.chunking.application;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.UUID;

import com.nexusagent.chunking.domain.ChunkedDocument;
import com.nexusagent.chunking.domain.ExtractedDocumentText;
import com.nexusagent.chunking.domain.ParentChildChunkPlan;
import com.nexusagent.chunking.repository.ChunkRepository;
import com.nexusagent.common.error.BadRequestException;
import com.nexusagent.common.error.NotFoundException;
import com.nexusagent.documents.domain.DocumentMetadata;
import com.nexusagent.documents.domain.DocumentStatus;
import com.nexusagent.documents.repository.DocumentRepository;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

@Service
public class DocumentChunkingService {

    private final DocumentRepository documentRepository;
    private final DocumentTextExtractionService textExtractionService;
    private final ParentChildChunker parentChildChunker;
    private final ChunkRepository chunkRepository;
    private final Clock clock;

    public DocumentChunkingService(
            DocumentRepository documentRepository,
            DocumentTextExtractionService textExtractionService,
            ParentChildChunker parentChildChunker,
            ChunkRepository chunkRepository,
            Clock clock
    ) {
        this.documentRepository = documentRepository;
        this.textExtractionService = textExtractionService;
        this.parentChildChunker = parentChildChunker;
        this.chunkRepository = chunkRepository;
        this.clock = clock;
    }

    public Mono<ChunkedDocument> extractAndChunk(UUID documentId) {
        return extractAndChunk(documentId, false);
    }

    public Mono<ChunkedDocument> extractAndChunk(UUID documentId, boolean force) {
        return documentRepository.findById(documentId)
                .switchIfEmpty(Mono.error(new NotFoundException("Document not found: " + documentId)))
                .flatMap(document -> chunkDocument(document, force)
                        .onErrorResume(error -> markChunkingFailed(document.id())
                                .onErrorResume(statusError -> Mono.empty())
                                .then(Mono.error(error))));
    }

    public Mono<ChunkedDocument> getChunks(UUID documentId) {
        return documentRepository.findById(documentId)
                .switchIfEmpty(Mono.error(new NotFoundException("Document not found: " + documentId)))
                .then(chunkRepository.findByDocumentId(documentId));
    }

    private Mono<ChunkedDocument> chunkDocument(DocumentMetadata document, boolean force) {
        if (force) {
            return regenerateChunks(document);
        }

        return chunkRepository.findByDocumentId(document.id())
                .flatMap(existing -> {
                    if (hasPersistedChunks(existing)) {
                        return markChunkedIfNeeded(document).thenReturn(existing);
                    }
                    return regenerateChunks(document);
                });
    }

    private boolean hasPersistedChunks(ChunkedDocument existing) {
        return !existing.parentChunks().isEmpty() && !existing.childChunks().isEmpty();
    }

    private Mono<ChunkedDocument> regenerateChunks(DocumentMetadata document) {
        return textExtractionService.extract(document)
                .map(this::requireNonBlankText)
                .map(extractedText -> parentChildChunker.chunk(extractedText.text()))
                .flatMap(plan -> persistPlan(document.id(), plan))
                .flatMap(chunkedDocument -> markChunked(document.id()).thenReturn(chunkedDocument));
    }

    private ExtractedDocumentText requireNonBlankText(ExtractedDocumentText extractedText) {
        if (extractedText.text() == null || extractedText.text().isBlank()) {
            throw new BadRequestException("Extracted document text is empty");
        }
        return extractedText;
    }

    private Mono<ChunkedDocument> persistPlan(UUID documentId, ParentChildChunkPlan plan) {
        OffsetDateTime now = OffsetDateTime.now(clock);
        return chunkRepository.replaceChunks(documentId, plan, now);
    }

    private Mono<Void> markChunked(UUID documentId) {
        return documentRepository.updateStatus(documentId, DocumentStatus.CHUNKED, OffsetDateTime.now(clock));
    }

    private Mono<Void> markChunkedIfNeeded(DocumentMetadata document) {
        if (document.status() == DocumentStatus.CHUNKED) {
            return Mono.empty();
        }
        return markChunked(document.id());
    }

    private Mono<Void> markChunkingFailed(UUID documentId) {
        return documentRepository.updateStatus(documentId, DocumentStatus.CHUNKING_FAILED, OffsetDateTime.now(clock));
    }
}
