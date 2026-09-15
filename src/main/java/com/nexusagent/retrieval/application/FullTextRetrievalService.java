package com.nexusagent.retrieval.application;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import com.nexusagent.common.context.RequestContext;
import com.nexusagent.common.observation.StageObservation;
import com.nexusagent.retrieval.domain.FullTextRetrievalCandidate;
import com.nexusagent.retrieval.repository.FullTextSearchRepository;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

@Service
public class FullTextRetrievalService {

    private final FullTextSearchRepository fullTextSearchRepository;

    public FullTextRetrievalService(FullTextSearchRepository fullTextSearchRepository) {
        this.fullTextSearchRepository = fullTextSearchRepository;
    }

    public Flux<FullTextRetrievalCandidate> retrieve(String query, List<UUID> documentIds, int topK) {
        return retrieve(query, documentIds, topK, RequestContext.defaults());
    }

    public Flux<FullTextRetrievalCandidate> retrieve(
            String query,
            List<UUID> documentIds,
            int topK,
            RequestContext context
    ) {
        return Flux.defer(() -> {
            AtomicInteger rank = new AtomicInteger(1);
            return StageObservation.observe("full_text_search",
                            () -> fullTextSearchRepository.search(query, documentIds, topK, context).collectList(),
                            rows -> Map.of("candidateCount", rows.size()))
                .flatMapMany(Flux::fromIterable)
                .map(result -> new FullTextRetrievalCandidate(
                        result.childChunkId(),
                        result.documentId(),
                        result.parentChunkId(),
                        result.chunkIndex(),
                        result.previewText(),
                        rank.getAndIncrement(),
                        result.score()
                ));
        });
    }
}
