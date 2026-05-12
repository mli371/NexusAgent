package com.nexusagent.embeddings.domain;

import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;

public record EmbeddingVector(List<Float> values) {

    public EmbeddingVector {
        values = List.copyOf(values);
    }

    public int dimension() {
        return values.size();
    }

    public String toPgVectorLiteral() {
        return values.stream()
                .map(value -> String.format(Locale.ROOT, "%.8f", value))
                .collect(Collectors.joining(",", "[", "]"));
    }
}
