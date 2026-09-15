package com.nexusagent.embeddings.application;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Set;

import com.nexusagent.chunking.domain.ChildChunk;
import com.nexusagent.chunking.domain.ChunkedDocument;
import com.nexusagent.chunking.repository.ChunkRepository;
import com.nexusagent.common.context.RequestContext;
import com.nexusagent.common.error.NotFoundException;
import com.nexusagent.common.error.OperationException;
import com.nexusagent.documents.repository.DocumentRepository;
import com.nexusagent.embeddings.domain.EmbeddingModelInfo;
import com.nexusagent.embeddings.domain.EmbeddingVector;
import com.nexusagent.embeddings.repository.ChildChunkEmbeddingRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/** Short commit boundary only. The caller has already generated the vectors. */
@Service
public class EmbeddingWriteService {
    private final DocumentRepository documents;
    private final ChunkRepository chunks;
    private final ChildChunkEmbeddingRepository embeddings;

    public EmbeddingWriteService(DocumentRepository documents, ChunkRepository chunks, ChildChunkEmbeddingRepository embeddings) {
        this.documents = documents;
        this.chunks = chunks;
        this.embeddings = embeddings;
    }

    @Transactional
    public Mono<Void> commit(ChunkedDocument snapshot, List<PendingEmbedding> generated, EmbeddingModelInfo model,
                             RequestContext context, boolean replaceExisting, OffsetDateTime timestamp) {
        return documents.lockAccessible(snapshot.documentId(), context)
                .switchIfEmpty(Mono.error(new NotFoundException("Document not found")))
                .then(chunks.findByDocumentId(snapshot.documentId()))
                .flatMap(current -> {
                    // Compare complete immutable child records, not counts alone.
                    if (!current.childChunks().equals(snapshot.childChunks())) {
                        return Mono.error(OperationException.documentChanged());
                    }
                    if (replaceExisting && !generated.stream().map(PendingEmbedding::child).toList().equals(snapshot.childChunks())) {
                        return Mono.error(new IllegalArgumentException("Replacement must cover the complete child snapshot"));
                    }
                    if (generated.stream().anyMatch(item -> !snapshot.childChunks().contains(item.child()))) {
                        return Mono.error(new IllegalArgumentException("Embedding does not belong to the child snapshot"));
                    }
                    return embeddings.coverage(snapshot.documentId(), model)
                            .flatMap(coverage -> {
                                if (!replaceExisting && coverage.mismatchedCount() > 0) {
                                    return Mono.error(OperationException.modelMismatch());
                                }
                                return embeddings.findEmbeddedChildChunkIds(snapshot.documentId()).collectList();
                            })
                            .flatMap(existing -> {
                                Set<java.util.UUID> ids = Set.copyOf(existing);
                                return Flux.fromIterable(generated)
                                        .filter(item -> replaceExisting || !ids.contains(item.child().id()))
                                        .concatMap(item -> embeddings.upsert(item.child(), item.vector(), model, timestamp)).then();
                            });
                });
    }

    public record PendingEmbedding(ChildChunk child, EmbeddingVector vector) { }
}
