package com.nexusagent.embeddings.application;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class EmbeddingConfiguration {

    @Bean
    @ConditionalOnProperty(name = "nexus.embeddings.provider", havingValue = "local", matchIfMissing = true)
    EmbeddingProvider localEmbeddingProvider(EmbeddingProperties properties) {
        return new LocalDeterministicEmbeddingProvider(properties.getDimension());
    }

    @Bean
    @ConditionalOnProperty(name = "nexus.embeddings.provider", havingValue = "spring-ai")
    EmbeddingProvider springAiEmbeddingProvider(ObjectProvider<SpringAiEmbeddingClient> clientProvider) {
        SpringAiEmbeddingClient client = clientProvider.getIfAvailable();
        if (client == null) {
            throw new IllegalStateException(
                    "nexus.embeddings.provider=spring-ai requires a SpringAiEmbeddingClient bean"
            );
        }
        return new SpringAiEmbeddingProvider(client);
    }
}
