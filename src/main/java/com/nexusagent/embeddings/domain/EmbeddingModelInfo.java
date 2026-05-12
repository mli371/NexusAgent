package com.nexusagent.embeddings.domain;

public record EmbeddingModelInfo(
        String provider,
        String modelName,
        int dimension
) {
}
