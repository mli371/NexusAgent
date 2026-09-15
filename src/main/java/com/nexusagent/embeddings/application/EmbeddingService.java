package com.nexusagent.embeddings.application;

import com.nexusagent.common.error.BadRequestException;
import com.nexusagent.embeddings.domain.EmbeddingModelInfo;
import com.nexusagent.embeddings.domain.EmbeddingVector;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

@Service
public class EmbeddingService {

    private final EmbeddingProvider embeddingProvider;

    public EmbeddingService(EmbeddingProvider embeddingProvider) {
        this.embeddingProvider = embeddingProvider;
    }

    public Mono<EmbeddingVector> embed(String text) {
        return embeddingProvider.embed(text)
                .map(this::requireExpectedDimension);
    }

    public EmbeddingModelInfo modelInfo() {
        return embeddingProvider.modelInfo();
    }

    public EmbeddingVector requireExpectedDimension(EmbeddingVector embedding) {
        int expectedDimension = modelInfo().dimension();
        if (embedding.dimension() != expectedDimension) {
            throw new BadRequestException(
                    "Embedding provider returned dimension %d but expected %d"
                            .formatted(embedding.dimension(), expectedDimension)
            );
        }
        double normSquared = 0;
        for (Float value : embedding.values()) {
            if (value == null || !Float.isFinite(value)) {
                throw com.nexusagent.common.error.OperationException.invalidModelResponse();
            }
            normSquared += (double) value * value;
        }
        if (!(normSquared > 0)) { throw com.nexusagent.common.error.OperationException.invalidModelResponse(); }
        return embedding;
    }
}
