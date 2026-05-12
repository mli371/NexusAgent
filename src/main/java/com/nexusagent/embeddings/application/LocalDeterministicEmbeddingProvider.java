package com.nexusagent.embeddings.application;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

import com.nexusagent.common.error.BadRequestException;
import com.nexusagent.embeddings.domain.EmbeddingModelInfo;
import com.nexusagent.embeddings.domain.EmbeddingVector;
import reactor.core.publisher.Mono;

public class LocalDeterministicEmbeddingProvider implements EmbeddingProvider {

    public static final String PROVIDER_NAME = "local";

    private static final Pattern TOKEN_SPLIT = Pattern.compile("[^\\p{Alnum}]+");

    private final EmbeddingModelInfo modelInfo;

    public LocalDeterministicEmbeddingProvider(int dimension) {
        if (dimension < 1) {
            throw new IllegalArgumentException("Embedding dimension must be greater than 0");
        }
        this.modelInfo = new EmbeddingModelInfo(
                PROVIDER_NAME,
                "local-deterministic-hash-%d".formatted(dimension),
                dimension
        );
    }

    @Override
    public Mono<EmbeddingVector> embed(String text) {
        return Mono.fromSupplier(() -> embedNow(text));
    }

    @Override
    public EmbeddingModelInfo modelInfo() {
        return modelInfo;
    }

    private EmbeddingVector embedNow(String text) {
        if (text == null || text.isBlank()) {
            throw new BadRequestException("Cannot embed blank text");
        }

        float[] values = new float[modelInfo.dimension()];
        String[] tokens = TOKEN_SPLIT.split(text.toLowerCase());
        int tokenCount = 0;
        for (String token : tokens) {
            if (token.isBlank()) {
                continue;
            }
            tokenCount++;
            addToken(values, token);
        }

        if (tokenCount == 0) {
            throw new BadRequestException("Cannot embed text without tokens");
        }

        normalize(values);
        List<Float> boxed = new ArrayList<>(values.length);
        for (float value : values) {
            boxed.add(value);
        }
        return new EmbeddingVector(boxed);
    }

    private void addToken(float[] values, String token) {
        byte[] digest = sha256(token);
        int index = Math.floorMod(toInt(digest, 0), values.length);
        float weight = 1.0f + (Math.floorMod(toInt(digest, 4), 1000) / 10000.0f);
        values[index] += weight;
    }

    private byte[] sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return digest.digest(value.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is required by the JDK", exception);
        }
    }

    private int toInt(byte[] bytes, int offset) {
        return ((bytes[offset] & 0xff) << 24)
                | ((bytes[offset + 1] & 0xff) << 16)
                | ((bytes[offset + 2] & 0xff) << 8)
                | (bytes[offset + 3] & 0xff);
    }

    private void normalize(float[] values) {
        double sumSquares = 0.0;
        for (float value : values) {
            sumSquares += value * value;
        }
        if (sumSquares == 0.0) {
            return;
        }

        float norm = (float) Math.sqrt(sumSquares);
        for (int index = 0; index < values.length; index++) {
            values[index] = values[index] / norm;
        }
    }
}
