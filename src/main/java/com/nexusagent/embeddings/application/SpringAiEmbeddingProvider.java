package com.nexusagent.embeddings.application;

import com.nexusagent.embeddings.domain.EmbeddingModelInfo;
import com.nexusagent.embeddings.domain.EmbeddingVector;
import reactor.core.publisher.Mono;

public class SpringAiEmbeddingProvider implements EmbeddingProvider {

    private final SpringAiEmbeddingClient client;

    public SpringAiEmbeddingProvider(SpringAiEmbeddingClient client) {
        this.client = client;
    }

    @Override
    public Mono<EmbeddingVector> embed(String text) {
        return client.embed(text);
    }

    @Override
    public EmbeddingModelInfo modelInfo() {
        return client.modelInfo();
    }
}
