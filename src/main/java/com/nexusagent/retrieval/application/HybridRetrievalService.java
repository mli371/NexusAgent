package com.nexusagent.retrieval.application;

import java.util.List;
import java.util.UUID;

import com.nexusagent.common.error.BadRequestException;
import com.nexusagent.retrieval.domain.HybridRetrievalResult;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

@Service
public class HybridRetrievalService {

    private final SemanticRetrievalService semanticRetrievalService;
    private final FullTextRetrievalService fullTextRetrievalService;
    private final RrfFusionService rrfFusionService;
    private final RetrievalProperties retrievalProperties;

    public HybridRetrievalService(
            SemanticRetrievalService semanticRetrievalService,
            FullTextRetrievalService fullTextRetrievalService,
            RrfFusionService rrfFusionService,
            RetrievalProperties retrievalProperties
    ) {
        this.semanticRetrievalService = semanticRetrievalService;
        this.fullTextRetrievalService = fullTextRetrievalService;
        this.rrfFusionService = rrfFusionService;
        this.retrievalProperties = retrievalProperties;
    }

    public Mono<HybridRetrievalResult> retrieve(String query, List<UUID> documentIds, Integer requestedTopK) {
        return Mono.defer(() -> {
            String normalizedQuery = normalizeQuery(query);
            List<UUID> normalizedDocumentIds = documentIds == null ? List.of() : List.copyOf(documentIds);
            int topK = normalizeTopK(requestedTopK);

            return Mono.zip(
                            semanticRetrievalService.retrieve(normalizedQuery, normalizedDocumentIds, topK).collectList(),
                            fullTextRetrievalService.retrieve(normalizedQuery, normalizedDocumentIds, topK).collectList()
                    )
                    .map(tuple -> new HybridRetrievalResult(
                            normalizedQuery,
                            tuple.getT1(),
                            tuple.getT2(),
                            rrfFusionService.fuse(tuple.getT1(), tuple.getT2(), topK)
                    ));
        });
    }

    private String normalizeQuery(String query) {
        if (query == null || query.isBlank()) {
            throw new BadRequestException("query must not be blank");
        }
        return query.trim();
    }

    private int normalizeTopK(Integer requestedTopK) {
        if (requestedTopK == null) {
            return retrievalProperties.getDefaultTopK();
        }
        if (requestedTopK < 1) {
            throw new BadRequestException("topK must be greater than 0");
        }
        return Math.min(requestedTopK, retrievalProperties.getMaxTopK());
    }
}
