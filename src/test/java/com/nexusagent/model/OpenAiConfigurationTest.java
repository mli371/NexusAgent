package com.nexusagent.model;

import static org.assertj.core.api.Assertions.assertThat;

import com.nexusagent.embeddings.application.EmbeddingConfiguration;
import com.nexusagent.embeddings.application.EmbeddingProperties;
import com.nexusagent.embeddings.application.EmbeddingProvider;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

class OpenAiConfigurationTest {
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(Properties.class, EmbeddingConfiguration.class, OpenAiConfiguration.class);

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties({OpenAiProperties.class, EmbeddingProperties.class})
    static class Properties { }

    @Test
    void localModeDoesNotRequireKey() {
        runner.run(context -> {
            assertThat(context).hasNotFailed().hasSingleBean(EmbeddingProvider.class).doesNotHaveBean(OpenAiHttpClient.class);
            assertThat(context.getBean(EmbeddingProvider.class).modelInfo().provider()).isEqualTo("local");
        });
    }

    @Test
    void realModeWithoutKeyFailsRatherThanFallingBack() {
        runner.withPropertyValues("nexus.embeddings.provider=openai").run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure()).hasRootCauseMessage(
                    "OpenAI requires NEXUS_LLM_API_KEY; no local fallback is enabled");
        });
    }

    @Test
    void realProviderCanBeConfiguredWithoutMakingAnyNetworkRequest() {
        runner.withPropertyValues("nexus.embeddings.provider=openai", "nexus.openai.api-key=unit-test-not-a-real-key")
                .run(context -> {
                    assertThat(context).hasNotFailed().hasSingleBean(EmbeddingProvider.class);
                    assertThat(context.getBean(EmbeddingProvider.class).modelInfo().provider()).isEqualTo("openai");
                });
    }

    @Test
    void rejectsDimensionIncompatibleWithDatabase() {
        runner.withPropertyValues("nexus.embeddings.provider=openai", "nexus.openai.api-key=unit-test-not-a-real-key",
                        "nexus.embeddings.dimension=1536")
                .run(context -> assertThat(context).hasFailed());
    }
}
