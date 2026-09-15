package com.nexusagent.embeddings.application;

import java.util.ArrayList;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.nexusagent.common.error.BadRequestException;
import com.nexusagent.embeddings.domain.EmbeddingModelInfo;
import com.nexusagent.embeddings.domain.EmbeddingVector;
import com.nexusagent.model.OpenAiHttpClient;
import com.nexusagent.model.OpenAiProperties;
import reactor.core.publisher.Mono;

public class OpenAiEmbeddingProvider implements EmbeddingProvider {
    private final OpenAiHttpClient client;
    private final OpenAiProperties properties;
    private final EmbeddingModelInfo model;

    public OpenAiEmbeddingProvider(OpenAiHttpClient client, OpenAiProperties properties, EmbeddingProperties embeddings) {
        if (embeddings.getDimension() != 384 || !"text-embedding-3-small".equals(embeddings.getModel())) {
            throw new IllegalStateException("This slice supports text-embedding-3-small with dimension 384 only");
        }
        this.client = client;
        this.properties = properties;
        this.model = new EmbeddingModelInfo("openai", embeddings.getModel(), embeddings.getDimension());
    }

    @Override
    public EmbeddingModelInfo modelInfo() { return model; }

    @Override
    public Mono<EmbeddingVector> embed(String text) {
        return Mono.defer(() -> {
            if (text == null || text.isBlank() || text.length() > 8000) {
                return Mono.error(new BadRequestException("Embedding input must contain 1 to 8000 characters"));
            }
            return client.post("embeddings", Map.of("model", model.modelName(), "input", text,
                            "dimensions", model.dimension(), "encoding_format", "float"),
                            properties.getEmbeddingTimeout(), true)
                    .map(this::parse);
        });
    }

    private EmbeddingVector parse(JsonNode response) {
        JsonNode data = response.path("data");
        if (!model.modelName().equals(response.path("model").asText()) || !data.isArray() || data.size() != 1
                || !data.get(0).path("index").isIntegralNumber() || data.get(0).path("index").intValue() != 0) {
            throw OpenAiHttpClient.invalidResponse();
        }
        JsonNode values = data.get(0).path("embedding");
        if (!values.isArray() || values.size() != model.dimension()) { throw OpenAiHttpClient.invalidResponse(); }
        ArrayList<Float> vector = new ArrayList<>(values.size());
        double normSquared = 0;
        for (JsonNode value : values) {
            float number = value.floatValue();
            if (!value.isNumber() || !Float.isFinite(number)) { throw OpenAiHttpClient.invalidResponse(); }
            normSquared += (double) number * number;
            vector.add(number);
        }
        if (!(normSquared > 0)) { throw OpenAiHttpClient.invalidResponse(); }
        return new EmbeddingVector(vector);
    }
}
