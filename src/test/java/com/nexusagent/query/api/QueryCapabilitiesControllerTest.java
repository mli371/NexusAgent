package com.nexusagent.query.api;

import static org.mockito.Mockito.when;

import com.nexusagent.embeddings.application.EmbeddingService;
import com.nexusagent.embeddings.domain.EmbeddingModelInfo;
import com.nexusagent.query.application.AnswerGenerator;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.reactive.WebFluxTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.web.reactive.server.WebTestClient;

@WebFluxTest(QueryCapabilitiesController.class)
class QueryCapabilitiesControllerTest {
    @Autowired WebTestClient client;
    @MockBean EmbeddingService embeddings;
    @MockBean AnswerGenerator answers;

    @Test
    void reportsActiveComponentsWithoutClaimingLiveQueryOrExposingCredentials() {
        when(embeddings.modelInfo()).thenReturn(new EmbeddingModelInfo("openai", "text-embedding-3-small", 384));
        when(answers.name()).thenReturn("local-template");
        client.get().uri("/api/v1/query/capabilities").exchange().expectStatus().isOk().expectBody()
                .jsonPath("$.activeAnswerGenerator").isEqualTo("local-template")
                .jsonPath("$.embedding.provider").isEqualTo("openai")
                .jsonPath("$.liveQueryReady").isEqualTo(false)
                .jsonPath("$.apiKey").doesNotExist().jsonPath("$.baseUrl").doesNotExist();
    }
}
