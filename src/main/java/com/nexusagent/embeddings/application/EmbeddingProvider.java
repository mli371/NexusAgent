package com.nexusagent.embeddings.application;

import com.nexusagent.embeddings.domain.EmbeddingModelInfo;
import com.nexusagent.embeddings.domain.EmbeddingVector;
import reactor.core.publisher.Mono;

public interface EmbeddingProvider {

    Mono<EmbeddingVector> embed(String text);

    EmbeddingModelInfo modelInfo();
}
