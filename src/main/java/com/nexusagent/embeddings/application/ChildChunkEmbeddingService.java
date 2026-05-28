package com.nexusagent.embeddings.application;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import com.nexusagent.chunking.domain.ChildChunk;
import com.nexusagent.chunking.domain.ChunkedDocument;
import com.nexusagent.chunking.repository.ChunkRepository;
import com.nexusagent.common.context.RequestContext;
import com.nexusagent.common.error.BadRequestException;
import com.nexusagent.common.error.NotFoundException;
import com.nexusagent.documents.domain.DocumentMetadata;
import com.nexusagent.documents.repository.DocumentRepository;
import com.nexusagent.embeddings.domain.EmbeddingModelInfo;
import com.nexusagent.embeddings.domain.EmbeddingStatus;
import com.nexusagent.embeddings.repository.ChildChunkEmbeddingRepository;
import com.nexusagent.enterprise.audit.AuditEventType;
import com.nexusagent.enterprise.audit.AuditService;
import com.nexusagent.enterprise.ingestion.IngestionJobService;
import com.nexusagent.enterprise.ingestion.IngestionJobType;
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

    public ChildChunkEmbeddingService(
            DocumentRepository documentRepository,
            ChunkRepository chunkRepository,
            EmbeddingService embeddingService,
            ChildChunkEmbeddingRepository embeddingRepository,
            IngestionJobService ingestionJobService,
            AuditService auditService,
            Clock clock
    ) {
        this.documentRepository = documentRepository;
        this.chunkRepository = chunkRepository;
        this.embeddingService = embeddingService;
        this.embeddingRepository = embeddingRepository;
        this.ingestionJobService = ingestionJobService;
        this.auditService = auditService;
        this.clock = clock;
    }

    public Mono<EmbeddingStatus> embedDocument(UUID documentId) {
        return embedDocument(documentId, RequestContext.defaults());
    }

    public Mono<EmbeddingStatus> embedDocument(UUID documentId, RequestContext context) {
        RequestContext effectiveContext = context == null ? RequestContext.defaults() : context;
        return loadChunkedDocument(documentId, effectiveContext)
                .flatMap(loaded -> ingestionJobService.run(
                        loaded.document().id(),
                        loaded.document().tenantId(),
                        IngestionJobType.EMBED,
                        embedLoadedDocument(loaded)
                                .flatMap(status -> auditEmbedded(loaded.document(), effectiveContext, status)
                                        .thenReturn(status))
                ));
    }

    public Mono<EmbeddingStatus> getStatus(UUID documentId) {
        return getStatus(documentId, RequestContext.defaults());
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

    private Mono<EmbeddingStatus> embedLoadedDocument(LoadedChunkedDocument loaded) {
        ChunkedDocument chunkedDocument = loaded.chunkedDocument();
        return requireChildChunks(chunkedDocument)
                .then(Mono.defer(() -> embeddingRepository.findEmbeddedChildChunkIds(chunkedDocument.documentId()).collectList()))
                .map(Set::copyOf)
                .flatMapMany(embeddedIds -> Flux.fromIterable(chunkedDocument.childChunks())
                        .filter(childChunk -> !embeddedIds.contains(childChunk.id())))
                .concatMap(this::embedChildChunk)
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
            return Mono.error(new BadRequestException("Document must be chunked before embedding"));
        }
        return Mono.empty();
    }

    private Mono<Void> embedChildChunk(ChildChunk childChunk) {
        OffsetDateTime timestamp = OffsetDateTime.now(clock);
        EmbeddingModelInfo modelInfo = embeddingService.modelInfo();
        return embeddingService.embed(childChunk.text())
                .flatMap(embedding -> embeddingRepository.upsert(childChunk, embedding, modelInfo, timestamp))
                .then();
    }

    private Mono<EmbeddingStatus> statusFor(ChunkedDocument chunkedDocument) {
        return embeddingRepository.countByDocumentId(chunkedDocument.documentId())
                .map(embeddedCount -> toStatus(chunkedDocument, embeddedCount));
    }

    private EmbeddingStatus toStatus(ChunkedDocument chunkedDocument, long embeddedCount) {
        int childChunkCount = chunkedDocument.childChunks().size();
        int embeddedChildChunkCount = Math.toIntExact(embeddedCount);
        int missingChildChunkCount = Math.max(0, childChunkCount - embeddedChildChunkCount);
        EmbeddingModelInfo modelInfo = embeddingService.modelInfo();
        return new EmbeddingStatus(
                chunkedDocument.documentId(),
                childChunkCount,
                embeddedChildChunkCount,
                missingChildChunkCount,
                childChunkCount > 0 && missingChildChunkCount == 0,
                modelInfo.provider(),
                modelInfo.modelName(),
                modelInfo.dimension()
        );
    }

    private Mono<Void> auditEmbedded(DocumentMetadata document, RequestContext context, EmbeddingStatus status) {
        return auditService.record(
                context,
                "embed-" + document.id(),
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
        );
    }

    private record LoadedChunkedDocument(
            DocumentMetadata document,
            ChunkedDocument chunkedDocument
    ) {
    }
}
