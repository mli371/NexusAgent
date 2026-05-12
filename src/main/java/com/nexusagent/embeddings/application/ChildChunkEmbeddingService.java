package com.nexusagent.embeddings.application;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.Set;
import java.util.UUID;

import com.nexusagent.chunking.domain.ChildChunk;
import com.nexusagent.chunking.domain.ChunkedDocument;
import com.nexusagent.chunking.repository.ChunkRepository;
import com.nexusagent.common.error.BadRequestException;
import com.nexusagent.common.error.NotFoundException;
import com.nexusagent.documents.repository.DocumentRepository;
import com.nexusagent.embeddings.domain.EmbeddingModelInfo;
import com.nexusagent.embeddings.domain.EmbeddingStatus;
import com.nexusagent.embeddings.repository.ChildChunkEmbeddingRepository;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@Service
public class ChildChunkEmbeddingService {

    private final DocumentRepository documentRepository;
    private final ChunkRepository chunkRepository;
    private final EmbeddingService embeddingService;
    private final ChildChunkEmbeddingRepository embeddingRepository;
    private final Clock clock;

    public ChildChunkEmbeddingService(
            DocumentRepository documentRepository,
            ChunkRepository chunkRepository,
            EmbeddingService embeddingService,
            ChildChunkEmbeddingRepository embeddingRepository,
            Clock clock
    ) {
        this.documentRepository = documentRepository;
        this.chunkRepository = chunkRepository;
        this.embeddingService = embeddingService;
        this.embeddingRepository = embeddingRepository;
        this.clock = clock;
    }

    public Mono<EmbeddingStatus> embedDocument(UUID documentId) {
        return loadChunkedDocument(documentId)
                .flatMap(chunkedDocument -> requireChildChunks(chunkedDocument)
                        .then(Mono.defer(() -> embeddingRepository.findEmbeddedChildChunkIds(documentId).collectList()))
                        .map(Set::copyOf)
                        .flatMapMany(embeddedIds -> Flux.fromIterable(chunkedDocument.childChunks())
                                .filter(childChunk -> !embeddedIds.contains(childChunk.id())))
                        .concatMap(this::embedChildChunk)
                        .then(Mono.defer(() -> statusFor(chunkedDocument))));
    }

    public Mono<EmbeddingStatus> getStatus(UUID documentId) {
        return loadChunkedDocument(documentId)
                .flatMap(this::statusFor);
    }

    private Mono<ChunkedDocument> loadChunkedDocument(UUID documentId) {
        return documentRepository.findById(documentId)
                .switchIfEmpty(Mono.error(new NotFoundException("Document not found: " + documentId)))
                .then(chunkRepository.findByDocumentId(documentId));
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
}
