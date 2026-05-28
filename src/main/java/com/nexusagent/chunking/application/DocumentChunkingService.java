package com.nexusagent.chunking.application;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;

import com.nexusagent.chunking.domain.ChunkedDocument;
import com.nexusagent.chunking.domain.ExtractedDocumentText;
import com.nexusagent.chunking.domain.ParentChildChunkPlan;
import com.nexusagent.chunking.repository.ChunkRepository;
import com.nexusagent.common.context.RequestContext;
import com.nexusagent.common.error.BadRequestException;
import com.nexusagent.common.error.NotFoundException;
import com.nexusagent.documents.domain.DocumentMetadata;
import com.nexusagent.documents.domain.DocumentStatus;
import com.nexusagent.documents.repository.DocumentRepository;
import com.nexusagent.enterprise.audit.AuditEventType;
import com.nexusagent.enterprise.audit.AuditService;
import com.nexusagent.enterprise.ingestion.IngestionJobService;
import com.nexusagent.enterprise.ingestion.IngestionJobType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

@Service
public class DocumentChunkingService {

    private static final Logger log = LoggerFactory.getLogger(DocumentChunkingService.class);

    private final DocumentRepository documentRepository;
    private final DocumentTextExtractionService textExtractionService;
    private final ParentChildChunker parentChildChunker;
    private final ChunkRepository chunkRepository;
    private final IngestionJobService ingestionJobService;
    private final AuditService auditService;
    private final Clock clock;

    public DocumentChunkingService(
            DocumentRepository documentRepository,
            DocumentTextExtractionService textExtractionService,
            ParentChildChunker parentChildChunker,
            ChunkRepository chunkRepository,
            IngestionJobService ingestionJobService,
            AuditService auditService,
            Clock clock
    ) {
        this.documentRepository = documentRepository;
        this.textExtractionService = textExtractionService;
        this.parentChildChunker = parentChildChunker;
        this.chunkRepository = chunkRepository;
        this.ingestionJobService = ingestionJobService;
        this.auditService = auditService;
        this.clock = clock;
    }

    public Mono<ChunkedDocument> extractAndChunk(UUID documentId) {
        return extractAndChunk(documentId, RequestContext.defaults(), false);
    }

    public Mono<ChunkedDocument> extractAndChunk(UUID documentId, boolean force) {
        return extractAndChunk(documentId, RequestContext.defaults(), force);
    }

    public Mono<ChunkedDocument> extractAndChunk(UUID documentId, RequestContext context, boolean force) {
        RequestContext effectiveContext = context == null ? RequestContext.defaults() : context;
        return documentRepository.findById(documentId, effectiveContext)
                .switchIfEmpty(Mono.error(new NotFoundException("Document not found: " + documentId)))
                .flatMap(document -> ingestionJobService.run(
                        document.id(),
                        document.tenantId(),
                        force ? IngestionJobType.FORCE_RECHUNK : IngestionJobType.CHUNK,
                        chunkDocument(document, effectiveContext, force)
                                .onErrorResume(error -> markChunkingFailed(document.id())
                                        .onErrorResume(statusError -> Mono.empty())
                                        .then(Mono.error(error)))
                ));
    }

    public Mono<ChunkedDocument> getChunks(UUID documentId) {
        return getChunks(documentId, RequestContext.defaults());
    }

    public Mono<ChunkedDocument> getChunks(UUID documentId, RequestContext context) {
        return documentRepository.findById(documentId, context)
                .switchIfEmpty(Mono.error(new NotFoundException("Document not found: " + documentId)))
                .then(chunkRepository.findByDocumentId(documentId));
    }

    private Mono<ChunkedDocument> chunkDocument(DocumentMetadata document, RequestContext context, boolean force) {
        if (force) {
            return regenerateChunks(document, context, true);
        }

        return chunkRepository.findByDocumentId(document.id())
                .flatMap(existing -> {
                    if (hasPersistedChunks(existing)) {
                        return markChunkedIfNeeded(document)
                                .then(auditChunked(document, context, existing, false))
                                .thenReturn(existing);
                    }
                    return regenerateChunks(document, context, false);
                });
    }

    private boolean hasPersistedChunks(ChunkedDocument existing) {
        return !existing.parentChunks().isEmpty() && !existing.childChunks().isEmpty();
    }

    private Mono<ChunkedDocument> regenerateChunks(DocumentMetadata document, RequestContext context, boolean force) {
        return textExtractionService.extract(document)
                .map(this::requireNonBlankText)
                .map(extractedText -> parentChildChunker.chunk(extractedText.text()))
                .flatMap(plan -> persistPlan(document.id(), plan))
                .flatMap(chunkedDocument -> markChunked(document.id())
                        .then(auditChunked(document, context, chunkedDocument, force))
                        .thenReturn(chunkedDocument))
                .doOnSuccess(chunkedDocument -> log.info(
                        "document_chunked documentId={} tenantId={} force={} parentCount={} childCount={}",
                        document.id(),
                        document.tenantId(),
                        force,
                        chunkedDocument.parentChunks().size(),
                        chunkedDocument.childChunks().size()
                ));
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

    private Mono<Void> auditChunked(
            DocumentMetadata document,
            RequestContext context,
            ChunkedDocument chunkedDocument,
            boolean force
    ) {
        return auditService.record(
                context,
                (force ? "force-rechunk-" : "chunk-") + document.id(),
                force ? AuditEventType.FORCE_RECHUNKED : AuditEventType.DOCUMENT_CHUNKED,
                "document",
                document.id(),
                document.id(),
                Map.of(
                        "parentChunkCount", chunkedDocument.parentChunks().size(),
                        "childChunkCount", chunkedDocument.childChunks().size(),
                        "force", force
                )
        );
    }
}
