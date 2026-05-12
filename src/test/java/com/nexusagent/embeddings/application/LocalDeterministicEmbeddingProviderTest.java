package com.nexusagent.embeddings.application;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import reactor.test.StepVerifier;

class LocalDeterministicEmbeddingProviderTest {

    @Test
    void returnsDeterministicVectorWithConfiguredDimension() {
        LocalDeterministicEmbeddingProvider provider = new LocalDeterministicEmbeddingProvider(384);

        StepVerifier.create(provider.embed("Security policy access review"))
                .assertNext(first -> StepVerifier.create(provider.embed("Security policy access review"))
                        .assertNext(second -> {
                            assertThat(first.dimension()).isEqualTo(384);
                            assertThat(second.dimension()).isEqualTo(384);
                            assertThat(second.values()).isEqualTo(first.values());
                            assertThat(first.values()).anyMatch(value -> value != 0.0f);
                        })
                        .verifyComplete())
                .verifyComplete();
    }
}
