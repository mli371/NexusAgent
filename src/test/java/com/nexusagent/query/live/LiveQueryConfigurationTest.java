package com.nexusagent.query.live;

import static org.assertj.core.api.Assertions.assertThat;
import com.nexusagent.embeddings.application.EmbeddingConfiguration;
import com.nexusagent.embeddings.application.EmbeddingProperties;
import com.nexusagent.embeddings.application.EmbeddingService;
import com.nexusagent.model.OpenAiProperties;
import com.nexusagent.query.application.QueryProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

class LiveQueryConfigurationTest {
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(Properties.class, LiveQueryConfiguration.class, EmbeddingConfiguration.class)
            .withBean(ObjectMapper.class, ObjectMapper::new)
            .withBean(EmbeddingService.class);

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties({QueryProperties.class, EmbeddingProperties.class, OpenAiProperties.class})
    static class Properties { }

    @Test
    void offlineIsExplicitDefaultWithoutKeyOrLiveBean() {
        runner.run(context -> assertThat(context).hasNotFailed().doesNotHaveBean(LiveQueryService.class));
    }

    @Test
    void unknownAnswerProviderFailsInsteadOfSilentlyUsingLocal() {
        runner.withPropertyValues("nexus.query.answer-provider=typo")
                .run(context -> assertThat(context.getStartupFailure()).hasRootCauseMessage("nexus.query.answer-provider must be local or openai"));
    }

    @Test
    void liveAnswerWithLocalEmbeddingsFailsWithActionableMessage() {
        runner.withPropertyValues("nexus.query.answer-provider=openai")
                .run(context -> assertThat(context.getStartupFailure()).hasRootCauseMessage("Live answers require nexus.embeddings.provider=openai"));
    }

    @Test
    void rejectsZeroOrUnboundedTimeout() {
        for (String timeout : java.util.List.of("0s", "10m")) {
            runner.withPropertyValues("nexus.query.live-context-timeout=" + timeout)
                    .run(context -> assertThat(context).hasFailed());
        }
    }
}
