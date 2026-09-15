package com.nexusagent.retrieval.application;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import com.nexusagent.common.context.RequestContext;
import com.nexusagent.common.observation.StageObservation;
import com.nexusagent.embeddings.application.EmbeddingService;
import com.nexusagent.embeddings.domain.EmbeddingVector;
import com.nexusagent.embeddings.repository.VectorSearchRepository;
import com.nexusagent.retrieval.domain.SemanticRetrievalCandidate;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

@Service
public class SemanticRetrievalService {

    private final EmbeddingService embeddingService;
    private final VectorSearchRepository vectorSearchRepository;

    public SemanticRetrievalService(
            EmbeddingService embeddingService,
            VectorSearchRepository vectorSearchRepository
    ) {
        this.embeddingService = embeddingService;
        this.vectorSearchRepository = vectorSearchRepository;
    }

    public Flux<SemanticRetrievalCandidate> retrieve(String query, List<UUID> documentIds, int topK) {
        return retrieve(query, documentIds, topK, RequestContext.defaults());
    }

    public Flux<SemanticRetrievalCandidate> retrieve(
            String query,
            List<UUID> documentIds,
            int topK,
            RequestContext context
    ) {
        return retrieveInternal(query, null, documentIds, topK, context);
    }

    public Flux<SemanticRetrievalCandidate> retrieveWithEmbedding(String query, EmbeddingVector embedding,
                                                                 List<UUID> documentIds, int topK, RequestContext context) {
        java.util.Objects.requireNonNull(embedding, "Precomputed query embedding is required");
        return retrieveInternal(query, embedding, documentIds, topK, context);
    }

    private Flux<SemanticRetrievalCandidate> retrieveInternal(String query, EmbeddingVector precomputed,
                                                             List<UUID> documentIds, int topK, RequestContext context) {
        return Flux.defer(() -> {
            AtomicInteger rank = new AtomicInteger(1);
            var vector = precomputed == null
                    ? StageObservation.observe("query_embedding", () -> embeddingService.embed(query),
                            ignored -> Map.of("model", embeddingService.modelInfo().modelName()))
                    : reactor.core.publisher.Mono.fromSupplier(() -> embeddingService.requireExpectedDimension(precomputed));
            return vector
                .flatMapMany(embedding -> StageObservation.observe("vector_search",
                        () -> vectorSearchRepository.search(embedding, documentIds, topK, context).collectList(),
                        rows -> Map.of("candidateCount", rows.size())).flatMapMany(Flux::fromIterable))
                .map(result -> new SemanticRetrievalCandidate(
                        result.childChunkId(),
                        result.documentId(),
                        result.parentChunkId(),
                        result.chunkIndex(),
                        result.previewText(),
                        rank.getAndIncrement(),
                        result.distance()
                ));
        });
    }
}
