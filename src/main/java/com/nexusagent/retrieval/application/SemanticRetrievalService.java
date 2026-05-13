package com.nexusagent.retrieval.application;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import com.nexusagent.embeddings.application.EmbeddingService;
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
        AtomicInteger rank = new AtomicInteger(1);
        return embeddingService.embed(query)
                .flatMapMany(embedding -> vectorSearchRepository.search(embedding, documentIds, topK))
                .map(result -> new SemanticRetrievalCandidate(
                        result.childChunkId(),
                        result.documentId(),
                        result.parentChunkId(),
                        result.chunkIndex(),
                        result.previewText(),
                        rank.getAndIncrement(),
                        result.distance()
                ));
    }
}
