package com.nexusagent.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexusagent.common.error.OperationException;
import com.nexusagent.context.domain.Citation;
import com.nexusagent.context.domain.ContextBuildResult;
import com.nexusagent.embeddings.application.EmbeddingProperties;
import com.nexusagent.embeddings.application.OpenAiEmbeddingProvider;
import com.nexusagent.query.application.OpenAiAnswerGenerator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

class OpenAiAdaptersTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private final OpenAiProperties properties = new OpenAiProperties();
    private final AtomicInteger calls = new AtomicInteger();

    @Test
    void embeddingDimensionAndSingleInputContract() throws Exception {
        AtomicReference<Map<String, Object>> sent = new AtomicReference<>();
        OpenAiHttpClient client = capturing(embeddingResponse(384, 1), sent);
        StepVerifier.create(embedding(client).embed("Synthetic document"))
                .assertNext(vector -> {
                    assertThat(vector.dimension()).isEqualTo(384);
                    assertThat(vector.values().get(0)).isEqualTo(1f);
                }).verifyComplete();
        assertThat(sent.get()).containsEntry("dimensions", 384).containsEntry("encoding_format", "float")
                .containsEntry("model", "text-embedding-3-small").containsEntry("input", "Synthetic document");
    }

    @Test
    void rejectsWrongDimensionZeroVectorAndWrongModel() throws Exception {
        for (String response : List.of(embeddingResponse(3, 1), embeddingResponse(384, 0),
                embeddingResponse(384, 1).replace("text-embedding-3-small", "another-model"))) {
            StepVerifier.create(embedding(http(response, 200)).embed("Synthetic document"))
                    .expectErrorSatisfies(error -> assertThat(((OperationException) error).code()).isEqualTo("INVALID_MODEL_RESPONSE"))
                    .verify();
        }
    }

    @Test
    void malformedAndOversizedProviderBodiesAreNotExposed() {
        for (String response : List.of("sensitive malformed provider response", "x".repeat(300000))) {
            StepVerifier.create(embedding(http(response, 200)).embed("example"))
                    .expectErrorSatisfies(error -> {
                        assertThat(error).isInstanceOf(OperationException.class);
                        assertThat(error.getMessage()).doesNotContain("sensitive", "xxxx");
                        assertThat(error.getCause()).isNull();
                    }).verify();
        }
    }

    @Test
    void embeddingRetriesOnlyOnceAndHidesProviderErrorBody() {
        StepVerifier.create(embedding(http("secret-provider-error", 429)).embed("example"))
                .expectErrorSatisfies(error -> {
                    assertThat(((OperationException) error).code()).isEqualTo("MODEL_RATE_LIMITED");
                    assertThat(error.getMessage()).doesNotContain("secret");
                }).verify();
        assertThat(calls).hasValue(2);
    }

    @Test
    void rejectedKeyDoesNotRetry() {
        StepVerifier.create(embedding(http("secret", 401)).embed("example"))
                .expectError(OperationException.class).verify();
        assertThat(calls).hasValue(1);
    }

    @Test
    void timeoutIsBoundedAndSafe() {
        OpenAiHttpClient client = new OpenAiHttpClient(WebClient.builder().exchangeFunction(request -> Mono.never()).build());
        StepVerifier.withVirtualTime(() -> client.post("embeddings", Map.of(), Duration.ofSeconds(2), true))
                .thenAwait(Duration.ofSeconds(2)).expectErrorSatisfies(error ->
                        assertThat(((OperationException) error).code()).isEqualTo("MODEL_TIMEOUT")).verify();
    }

    @Test
    void answerUsesResponsesSchemaAndValidatesCitations() throws Exception {
        AtomicReference<Map<String, Object>> sent = new AtomicReference<>();
        String payload = answerResponse("answered", "The synthetic policy requires review [C1].", List.of("[C1]"));
        StepVerifier.create(answer(capturing(payload, sent)).generate("What is required?", context()))
                .assertNext(result -> {
                    assertThat(result.status()).isEqualTo("answered");
                    assertThat(result.usedCitationMarkers()).containsExactly("[C1]");
                }).verifyComplete();
        JsonNode request = mapper.valueToTree(sent.get());
        assertThat(request.path("model").asText()).isEqualTo("gpt-5.6-luna");
        assertThat(request.path("store").booleanValue()).isFalse();
        assertThat(request.path("text").path("format").path("strict").booleanValue()).isTrue();
        assertThat(request.at("/text/format/schema/properties/usedCitationMarkers/items/enum")).isEqualTo(mapper.valueToTree(List.of("[C1]")));
        assertThat(request.has("tools")).isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {"[C999]", "", "[C1] [C2]", "[Cbad]"})
    void rejectsUnknownOrInconsistentCitationMarkers(String marker) throws Exception {
        StepVerifier.create(answer(http(answerResponse("answered", "Claim " + marker, List.of("[C1]")), 200))
                        .generate("question", context()))
                .expectError(OperationException.class).verify();
    }

    @Test
    void emptyContextDoesNotCallModel() {
        ContextBuildResult empty = new ContextBuildResult("question", List.of(), List.of(), List.of(), List.of(), "", null);
        StepVerifier.create(answer(http("not used", 500)).generate("question", empty))
                .assertNext(result -> {
                    assertThat(result.status()).isEqualTo("insufficient_context");
                    assertThat(result.usedCitationMarkers()).isEmpty();
                }).verifyComplete();
        assertThat(calls).hasValue(0);
    }

    @Test
    void insufficientContextAndRefusalDoNotForwardUngroundedProviderText() throws Exception {
        for (String status : List.of("insufficient_context", "refused")) {
            StepVerifier.create(answer(http(answerResponse(status, "unsupported sensitive claim", List.of()), 200))
                            .generate("question", context()))
                    .assertNext(result -> {
                        assertThat(result.status()).isEqualTo(status);
                        assertThat(result.answer()).doesNotContain("sensitive");
                        assertThat(result.usedCitationMarkers()).isEmpty();
                    }).verifyComplete();
        }
    }

    @Test
    void incompleteOutputAndNativeRefusalAreHandled() throws Exception {
        String valid = answerResponse("answered", "Claim [C1]", List.of("[C1]"));
        StepVerifier.create(answer(http(valid.replace("completed", "incomplete"), 200)).generate("question", context()))
                .expectError(OperationException.class).verify();
        String refusal = mapper.writeValueAsString(Map.of("status", "completed", "output", List.of(Map.of(
                "type", "message", "role", "assistant", "content", List.of(Map.of("type", "refusal", "refusal", "private"))))));
        StepVerifier.create(answer(http(refusal, 200)).generate("question", context()))
                .assertNext(result -> assertThat(result.status()).isEqualTo("refused")).verifyComplete();
    }

    @ParameterizedTest
    @ValueSource(strings = {"[C1, C2]", "[C1,C2]", "[C1\uFF0CC2]", "[C1][C2]"})
    void canonicalizesOnlyKnownGroupedCitationsBeforeSetValidation(String markers) throws Exception {
        ContextBuildResult original = context();
        Citation second = new Citation(2, "[C2]", UUID.randomUUID(), "synthetic-two.md", UUID.randomUUID(),
                UUID.randomUUID(), 0, null, 0, 20, "Other synthetic text");
        ContextBuildResult two = new ContextBuildResult("q", List.of(), List.of(), List.of(),
                List.of(original.citations().get(0), second), original.finalContextText() + "\n[C2] Other synthetic text", null);
        StepVerifier.create(answer(http(answerResponse("answered", "Supported claim " + markers, List.of("[C1]", "[C2]")), 200))
                        .generate("question", two))
                .assertNext(result -> {
                    assertThat(result.answer()).isEqualTo("Supported claim [C1][C2]");
                    assertThat(result.usedCitationMarkers()).containsExactly("[C1]", "[C2]");
                }).verifyComplete();
        assertThat(calls).hasValue(1);
    }

    @ParameterizedTest
    @ValueSource(strings = {"[C1, C999]", "[C1-C2]", "[C1, Cbad]", "[C1, C2]"})
    void citationNormalizationNeverInventsUnknownOrUndeclaredReferences(String markers) throws Exception {
        StepVerifier.create(answer(http(answerResponse("answered", "Claim " + markers, List.of("[C1]")), 200))
                        .generate("question", context()))
                .expectError(OperationException.class).verify();
        assertThat(calls).hasValue(1);
    }

    @Test
    void doesNotRepairMissingOrBareDeclaredMarkers() throws Exception {
        for (List<String> declared : List.of(List.of("C1"), List.<String>of(), List.of("[C1]", "[C1]"))) {
            StepVerifier.create(answer(http(answerResponse("answered", "Claim [C1]", declared), 200)).generate("question", context()))
                    .expectError(OperationException.class).verify();
        }
        assertThat(calls).hasValue(3);
    }

    @Test
    void tokenLimitIsReportedSeparatelyAndNeverRetriedOrPartiallyParsed() throws Exception {
        var body = (com.fasterxml.jackson.databind.node.ObjectNode) mapper.readTree(answerResponse("answered", "Claim [C1]", List.of("[C1]")));
        body.put("status", "incomplete");
        body.set("incomplete_details", mapper.valueToTree(Map.of("reason", "max_output_tokens")));
        StepVerifier.create(answer(http(body.toString(), 200)).generate("question", context()))
                .expectErrorSatisfies(error -> {
                    assertThat(((OperationException) error).code()).isEqualTo("MODEL_OUTPUT_LIMIT");
                    assertThat(error.getMessage()).contains("output-token limit").doesNotContain("Claim");
                    assertThat(error.getCause()).isNull();
                }).verify();
        assertThat(calls).hasValue(1);
    }

    @Test
    void malformedAnswerJsonRemainsAnErrorWithoutLeakingText() throws Exception {
        var body = (com.fasterxml.jackson.databind.node.ObjectNode) mapper.readTree(answerResponse("answered", "Claim [C1]", List.of("[C1]")));
        ((com.fasterxml.jackson.databind.node.ObjectNode) body.at("/output/1/content/0")).put("text", "private-malformed-output");
        StepVerifier.create(answer(http(body.toString(), 200)).generate("question", context()))
                .expectErrorSatisfies(error -> {
                    assertThat(((OperationException) error).code()).isEqualTo("INVALID_MODEL_RESPONSE");
                    assertThat(error.getMessage()).contains("INVALID_JSON").doesNotContain("private");
                    assertThat(error.getCause()).isNull();
                }).verify();
    }

    @Test
    void answerDoesNotRetryOrFallbackOnProviderFailure() {
        StepVerifier.create(answer(http("private-provider-error", 503)).generate("question", context()))
                .expectError(OperationException.class).verify();
        assertThat(calls).hasValue(1);
    }

    @Test
    void keyValidationDoesNotExposeCredentials() {
        assertThatThrownBy(properties::validate).hasMessageContaining("NEXUS_LLM_API_KEY");
        properties.setApiKey("unit-test-not-a-real-key");
        properties.validate();
    }

    @Test
    void actualHttpRequestUsesCorrectPathAndJsonBody() throws Exception {
        AtomicReference<String> path = new AtomicReference<>();
        AtomicReference<String> body = new AtomicReference<>();
        String response = embeddingResponse(384, 1);
        reactor.netty.DisposableServer server = reactor.netty.http.server.HttpServer.create().host("127.0.0.1").port(0)
                .handle((request, reply) -> request.receive().aggregate().asString().flatMap(text -> {
                    path.set(request.uri());
                    body.set(text);
                    return reply.header("Content-Type", "application/json").sendString(Mono.just(response)).then();
                })).bindNow();
        try {
            OpenAiHttpClient client = new OpenAiHttpClient(WebClient.builder()
                    .baseUrl("http://127.0.0.1:" + server.port() + "/v1/").build());
            StepVerifier.create(embedding(client).embed("Synthetic test text"))
                    .assertNext(vector -> assertThat(vector.dimension()).isEqualTo(384)).verifyComplete();
            assertThat(path.get()).isEqualTo("/v1/embeddings");
            assertThat(mapper.readTree(body.get()).path("input").asText()).isEqualTo("Synthetic test text");
        } finally {
            server.disposeNow();
        }
    }

    @Test
    void invalidAndOversizedInputsDoNotCallProvider() {
        for (String text : List.of("", " ", "x".repeat(8001))) {
            StepVerifier.create(embedding(http("unused", 200)).embed(text))
                    .expectError(com.nexusagent.common.error.BadRequestException.class).verify();
        }
        ContextBuildResult large = new ContextBuildResult("q", List.of(), List.of(), List.of(), context().citations(),
                "x".repeat(65536), null);
        StepVerifier.create(answer(http("unused", 200)).generate("question", large))
                .expectError(com.nexusagent.common.error.BadRequestException.class).verify();
        assertThat(calls).hasValue(0);
    }

    private OpenAiEmbeddingProvider embedding(OpenAiHttpClient http) {
        return new OpenAiEmbeddingProvider(http, properties, new EmbeddingProperties());
    }

    private OpenAiAnswerGenerator answer(OpenAiHttpClient http) { return new OpenAiAnswerGenerator(http, properties, mapper); }

    private OpenAiHttpClient http(String response, int status) {
        return new OpenAiHttpClient(WebClient.builder().codecs(c -> c.defaultCodecs().maxInMemorySize(256 * 1024))
                .exchangeFunction(request -> {
                    calls.incrementAndGet();
                    return Mono.just(ClientResponse.create(HttpStatus.valueOf(status))
                            .header("Content-Type", MediaType.APPLICATION_JSON_VALUE).body(response).build());
                }).build());
    }

    private OpenAiHttpClient capturing(String response, AtomicReference<Map<String, Object>> sent) throws Exception {
        JsonNode json = mapper.readTree(response);
        return new OpenAiHttpClient(WebClient.create()) {
            @Override
            public Mono<JsonNode> post(String path, Map<String, Object> body, Duration timeout, boolean retry) {
                assertThat(path).isIn("embeddings", "responses");
                sent.set(body);
                return Mono.just(json);
            }
        };
    }

    private String embeddingResponse(int size, float first) throws Exception {
        var values = new java.util.ArrayList<>(java.util.Collections.nCopies(size, 0f));
        values.set(0, first);
        return mapper.writeValueAsString(Map.of("model", "text-embedding-3-small", "data", List.of(Map.of("index", 0, "embedding", values))));
    }

    private String answerResponse(String status, String text, List<String> markers) throws Exception {
        String answer = mapper.writeValueAsString(Map.of("status", status, "answer", text, "usedCitationMarkers", markers));
        return mapper.writeValueAsString(Map.of("status", "completed", "output", List.of(
                Map.of("type", "reasoning", "summary", List.of()),
                Map.of("type", "message", "role", "assistant", "content", List.of(Map.of("type", "output_text", "text", answer))))));
    }

    private ContextBuildResult context() {
        Citation citation = new Citation(1, "[C1]", UUID.randomUUID(), "synthetic.md", UUID.randomUUID(),
                UUID.randomUUID(), 0, null, 0, 30, "Synthetic policy requires review");
        return new ContextBuildResult("question", List.of(), List.of(), List.of(), List.of(citation),
                "[C1] Synthetic policy requires review", null);
    }
}
