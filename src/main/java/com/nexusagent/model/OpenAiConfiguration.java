package com.nexusagent.model;

import com.nexusagent.embeddings.application.EmbeddingProperties;
import com.nexusagent.embeddings.application.EmbeddingProvider;
import com.nexusagent.embeddings.application.OpenAiEmbeddingProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.web.reactive.function.client.WebClient;

@Configuration
@ConditionalOnProperty(name = "nexus.embeddings.provider", havingValue = "openai")
public class OpenAiConfiguration {
    @Bean
    OpenAiHttpClient openAiHttpClient(OpenAiProperties properties) {
        properties.validate();
        return new OpenAiHttpClient(WebClient.builder().baseUrl("https://api.openai.com/v1/")
                .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + properties.getApiKey())
                .codecs(codecs -> codecs.defaultCodecs().maxInMemorySize(256 * 1024)).build());
    }

    @Bean
    EmbeddingProvider openAiEmbeddingProvider(OpenAiHttpClient client, OpenAiProperties properties,
                                              EmbeddingProperties embeddings) {
        return new OpenAiEmbeddingProvider(client, properties, embeddings);
    }
}
