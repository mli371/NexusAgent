package com.nexusagent.embeddings.application;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import com.nexusagent.chunking.domain.ChunkedDocument;
import com.nexusagent.chunking.repository.ChunkRepository;
import com.nexusagent.common.context.RequestContext;
import com.nexusagent.common.error.BadRequestException;
import com.nexusagent.common.error.NotFoundException;
import com.nexusagent.common.error.OperationException;
import com.nexusagent.documents.domain.DocumentMetadata;
import com.nexusagent.documents.repository.DocumentRepository;
import com.nexusagent.embeddings.domain.EmbeddingModelInfo;
import com.nexusagent.embeddings.domain.EmbeddingStatus;
import com.nexusagent.embeddings.repository.ChildChunkEmbeddingRepository;
import com.nexusagent.enterprise.audit.AuditEventType;
import com.nexusagent.enterprise.audit.AuditService;
import com.nexusagent.enterprise.ingestion.IngestionJobService;
import com.nexusagent.enterprise.ingestion.IngestionJobType;
import com.nexusagent.enterprise.ingestion.IngestionFailure;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@Service
public class ChildChunkEmbeddingService {

    private static final Logger log = LoggerFactory.getLogger(ChildChunkEmbeddingService.class);

    private final DocumentRepository documentRepository;
    private final ChunkRepository chunkRepository;
    private final EmbeddingService embeddingService;
    private final ChildChunkEmbeddingRepository embeddingRepository;
    private final IngestionJobService ingestionJobService;
    private final AuditService auditService;
    private final Clock clock;
    private final EmbeddingWriteService embeddingWriteService;

    public ChildChunkEmbeddingService(
            DocumentRepository documentRepository,
            ChunkRepository chunkRepository,
            EmbeddingService embeddingService,
            ChildChunkEmbeddingRepository embeddingRepository,
            IngestionJobService ingestionJobService,
            AuditService auditService,
            Clock clock,
            EmbeddingWriteService embeddingWriteService
    ) {
        this.documentRepository = documentRepository;
        this.chunkRepository = chunkRepository;
        this.embeddingService = embeddingService;
        this.embeddingRepository = embeddingRepository;
        this.ingestionJobService = ingestionJobService;
        this.auditService = auditService;
        this.clock = clock;
        this.embeddingWriteService = embeddingWriteService;
    }

    public Mono<EmbeddingStatus> embedDocument(UUID documentId) {
        return embedDocument(documentId, RequestContext.defaults());
    }

    public Mono<EmbeddingStatus> embedDocument(UUID documentId, RequestContext context) {
        return embedDocument(documentId, context, false);
    }

    public Mono<EmbeddingStatus> embedDocument(UUID documentId, RequestContext context, boolean replaceExisting) {
        RequestContext effectiveContext = context == null ? RequestContext.defaults() : context;
        return loadChunkedDocument(documentId, effectiveContext)
                .flatMap(loaded -> ingestionJobService.run(
                        loaded.document().id(),
                        loaded.document().tenantId(),
                        replaceExisting ? IngestionJobType.REEMBED : IngestionJobType.EMBED,
                        embedLoadedDocument(loaded, effectiveContext, replaceExisting)
                                .flatMap(status -> auditEmbedded(loaded.document(), effectiveContext, status)
                                        .thenReturn(status))
                ));
    }

    public Mono<EmbeddingStatus> getStatus(UUID documentId) {
        return getStatus(documentId, RequestContext.defaults());
    }

    /** Does not create a second job or replace existing embeddings. */
    public Mono<EmbeddingStatus> executeApproved(UUID documentId, RequestContext context, UUID jobId, String traceId) {
        return ingestionJobService.runExisting(jobId, documentId, context.tenantId(), IngestionJobType.EMBED,
                loadChunkedDocument(documentId, context).flatMap(loaded -> embedLoadedDocument(loaded, context, false)
                        .flatMap(status -> auditEmbedded(loaded.document(), context, status).thenReturn(status))))
                .contextWrite(values -> values.put("ingestionTraceId", traceId));
    }

    public Mono<EmbeddingStatus> getStatus(UUID documentId, RequestContext context) {
        return loadChunkedDocument(documentId, context)
                .flatMap(loaded -> statusFor(loaded.chunkedDocument()));
    }

    private Mono<LoadedChunkedDocument> loadChunkedDocument(UUID documentId, RequestContext context) {
        return documentRepository.findById(documentId, context)
                .switchIfEmpty(Mono.error(new NotFoundException("Document not found: " + documentId)))
                .flatMap(document -> chunkRepository.findByDocumentId(documentId)
                        .map(chunkedDocument -> new LoadedChunkedDocument(document, chunkedDocument)));
    }

    private Mono<EmbeddingStatus> embedLoadedDocument(LoadedChunkedDocument loaded, RequestContext context, boolean replaceExisting) {
        ChunkedDocument chunkedDocument = loaded.chunkedDocument();
        EmbeddingModelInfo model = embeddingService.modelInfo();
        if (replaceExisting && (chunkedDocument.childChunks().size() > 256
                || chunkedDocument.childChunks().stream().mapToLong(child -> child.text().length()).sum() > 1_000_000)) {
            return Mono.error(new BadRequestException("Re-embedding is limited to 256 child chunks and 1000000 characters"));
        }
        return requireChildChunks(chunkedDocument)
                .then(Mono.defer(() -> embeddingRepository.coverage(chunkedDocument.documentId(), model)))
                .flatMap(coverage -> !replaceExisting && coverage.mismatchedCount() > 0
                        ? Mono.error(OperationException.modelMismatch())
                        : embeddingRepository.findEmbeddedChildChunkIds(chunkedDocument.documentId()).collectList())
                .map(Set::copyOf)
                .flatMap(embeddedIds -> {
                    Flux<EmbeddingWriteService.PendingEmbedding> generated = Flux.fromIterable(chunkedDocument.childChunks())
                            .filter(child -> replaceExisting || !embeddedIds.contains(child.id()))
                            .concatMap(child -> documentRepository.findById(chunkedDocument.documentId(), context)
                                    .switchIfEmpty(Mono.error(new NotFoundException("Document not found")))
                                    .flatMap(document -> embeddingService.embed(child.text()))
                                    .map(vector -> new EmbeddingWriteService.PendingEmbedding(child, vector)));
                    if (replaceExisting) {
                        return generated.collectList().flatMap(vectors -> embeddingWriteService.commit(
                                chunkedDocument, vectors, model, context, true, OffsetDateTime.now(clock)));
                    }
                    return generated.concatMap(vector -> embeddingWriteService.commit(chunkedDocument,
                            List.of(vector), model, context, false, OffsetDateTime.now(clock))).then();
                })
                .then(Mono.defer(() -> statusFor(chunkedDocument)))
                .doOnSuccess(status -> log.info(
                        "document_embedded documentId={} tenantId={} childChunks={} embeddedChildChunks={} complete={}",
                        loaded.document().id(),
                        loaded.document().tenantId(),
                        status.childChunkCount(),
                        status.embeddedChildChunkCount(),
                        status.complete()
                ));
    }

    private Mono<Void> requireChildChunks(ChunkedDocument chunkedDocument) {
        if (chunkedDocument.childChunks().isEmpty()) {
            return Mono.error(new IngestionFailure("CHUNKING_REQUIRED", "Document must be chunked before embedding"));
        }
        return Mono.empty();
    }

    private Mono<EmbeddingStatus> statusFor(ChunkedDocument chunkedDocument) {
        EmbeddingModelInfo modelInfo = embeddingService.modelInfo();
        return embeddingRepository.coverage(chunkedDocument.documentId(), modelInfo)
                .map(coverage -> new EmbeddingStatus(chunkedDocument.documentId(), coverage.childCount(),
                        coverage.embeddedCount(), coverage.missingCount(), coverage.complete(), modelInfo.provider(),
                        modelInfo.modelName(), modelInfo.dimension(), coverage.matchingCount(), coverage.mismatchedCount()));
    }

    private Mono<Void> auditEmbedded(DocumentMetadata document, RequestContext context, EmbeddingStatus status) {
        return Mono.deferContextual(values -> auditService.record(
                context,
                values.getOrDefault("ingestionTraceId", "embed-" + document.id()),
                AuditEventType.DOCUMENT_EMBEDDED,
                "document",
                document.id(),
                document.id(),
                Map.of(
                        "childChunkCount", status.childChunkCount(),
                        "embeddedChildChunkCount", status.embeddedChildChunkCount(),
                        "missingChildChunkCount", status.missingChildChunkCount(),
                        "provider", status.provider(),
                        "modelName", status.modelName(),
                        "dimension", status.dimension()
                )
        ));
    }

    private record LoadedChunkedDocument(
            DocumentMetadata document,
            ChunkedDocument chunkedDocument
    ) {
    }
}
