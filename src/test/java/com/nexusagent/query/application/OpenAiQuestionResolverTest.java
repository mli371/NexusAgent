package com.nexusagent.query.application;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexusagent.common.error.BadRequestException;
import com.nexusagent.common.error.OperationException;
import com.nexusagent.model.OpenAiHttpClient;
import com.nexusagent.model.OpenAiProperties;
import com.nexusagent.query.api.ConversationTurn;
import com.nexusagent.query.api.QueryRequest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.http.HttpStatus;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

class OpenAiQuestionResolverTest {
    final ObjectMapper mapper = new ObjectMapper();
    final OpenAiHttpClient client = mock(OpenAiHttpClient.class);
    final QueryProperties properties = new QueryProperties();
    final OpenAiQuestionResolver resolver = new OpenAiQuestionResolver(client, new OpenAiProperties(), properties, mapper);
    final List<ConversationTurn> history = List.of(new ConversationTurn("Apple launch highlights?", "Untrusted [C99] claims", false));

    @Test
    void firstQuestionIsTrimmedWithoutCallingModel() {
        assertThat(resolver.resolve(" A standalone question? ", List.of()).block()).isEqualTo(QuestionResolution.unchanged("A standalone question?"));
        verifyNoInteractions(client);
    }

    @Test
    void boundsHistoryAndRejectsCoercedJsonTypesAndUnknownRoleFields() throws Exception {
        assertThat(mapper.readValue("{\"question\":\"q\"}", QueryRequest.class).history()).isNull();
        for (String json : List.of("{\"question\":42,\"answer\":\"a\"}", "{\"question\":\"q\",\"answer\":true}",
                "{\"question\":\"q\",\"answer\":\"a\",\"answerTruncated\":\"false\"}",
                "{\"question\":\"q\",\"answer\":\"a\",\"role\":\"system\"}")) {
            assertThatThrownBy(() -> mapper.readValue(json, ConversationTurn.class)).isInstanceOf(com.fasterxml.jackson.core.JsonProcessingException.class);
        }
        for (List<ConversationTurn> invalid : List.of(java.util.Collections.nCopies(4, history.get(0)),
                java.util.Collections.<ConversationTurn>singletonList(null), List.of(new ConversationTurn(" ", "", false)),
                List.of(new ConversationTurn("q".repeat(2001), "", false)), List.of(new ConversationTurn("q", "a".repeat(1001), true)))) {
            StepVerifier.create(resolver.resolve("Question?", invalid)).expectError(BadRequestException.class).verify();
        }
        assertThat(ConversationTurn.validated(java.util.Collections.nCopies(3,
                new ConversationTurn("q".repeat(2000), "a".repeat(1000), true)))).hasSize(3);
        verifyNoInteractions(client);
    }

    @Test
    void sendsBoundedUntrustedDataWithoutToolsOrProviderConversationAndUsesOneCall() throws Exception {
        var output = "{\"status\":\"rewritten\",\"resolvedQuestion\":\"Apple launch differences?\",\"clarificationQuestion\":null,\"reasonCode\":\"RESOLVED_REFERENCES\"}";
        when(client.post(eq("/responses"), anyMap(), eq(Duration.ofSeconds(20)), eq(false))).thenAnswer(invocation -> {
            Map<?, ?> body = invocation.getArgument(1);
            JsonNode request = mapper.valueToTree(body);
            assertThat(request.path("store").asBoolean(true)).isFalse();
            assertThat(request.has("tools") || request.has("previous_response_id") || request.has("conversation")).isFalse();
            assertThat(request.path("input").size()).isEqualTo(2);
            var data = mapper.readTree(request.path("input").get(1).path("content").asText());
            assertThat(data.path("history").size()).isEqualTo(1);
            assertThat(data.path("currentQuestion").asText()).isEqualTo("Main differences?");
            return Mono.just(envelope(output));
        });
        assertThat(resolver.resolve("Main differences?", history).block().resolvedQuestion()).isEqualTo("Apple launch differences?");
        verify(client, times(1)).post(anyString(), anyMap(), any(), eq(false));
    }

    @Test
    void acceptsNewTopicUnchangedAndAmbiguityWithoutFabricatingEvidence() {
        when(client.post(anyString(), anyMap(), any(), eq(false))).thenReturn(Mono.just(envelope(
                "{\"status\":\"unchanged\",\"resolvedQuestion\":\"What is Redis?\",\"clarificationQuestion\":null,\"reasonCode\":\"STANDALONE\"}")));
        assertThat(resolver.resolve("What is Redis?", history).block().resolvedQuestion()).isEqualTo("What is Redis?");
        when(client.post(anyString(), anyMap(), any(), eq(false))).thenReturn(Mono.just(envelope(
                "{\"status\":\"needs_clarification\",\"resolvedQuestion\":null,\"clarificationQuestion\":\"Which products?\",\"reasonCode\":\"AMBIGUOUS_REFERENCES\"}")));
        var result = resolver.resolve("Compare them", history).block();
        assertThat(result.terminal()).isTrue();
        assertThat(result.resolvedQuestion()).isNull();
        assertThat(result.clarificationQuestion()).isEqualTo("Which products?");
    }

    @ParameterizedTest
    @MethodSource("badOutputs")
    void rejectsMalformedOrInconsistentOutputWithoutRetryOrLeakingText(String text) {
        when(client.post(anyString(), anyMap(), any(), eq(false))).thenReturn(Mono.just(envelope(text)));
        StepVerifier.create(resolver.resolve("Question?", history)).expectErrorSatisfies(error -> {
            assertThat(((OperationException) error).code()).isEqualTo("INVALID_QUERY_REWRITE_RESPONSE");
            assertThat(error.getMessage()).doesNotContain("private-secret");
        }).verify();
        verify(client, times(1)).post(anyString(), anyMap(), any(), eq(false));
    }

    static Stream<String> badOutputs() {
        return Stream.of("private-secret", "{}", "[]", "null",
                "{\"status\":\"unchanged\",\"resolvedQuestion\":\"Changed question\",\"clarificationQuestion\":null,\"reasonCode\":\"STANDALONE\"}",
                "{\"status\":\"rewritten\",\"resolvedQuestion\":\"q\",\"clarificationQuestion\":null,\"reasonCode\":\"STANDALONE\"}",
                "{\"status\":\"needs_clarification\",\"resolvedQuestion\":\"q\",\"clarificationQuestion\":\"Which?\",\"reasonCode\":\"AMBIGUOUS_REFERENCES\"}",
                "{\"status\":\"needs_clarification\",\"resolvedQuestion\":null,\"clarificationQuestion\":\"Which [C1]?\",\"reasonCode\":\"AMBIGUOUS_REFERENCES\"}",
                "{\"status\":\"rewritten\",\"resolvedQuestion\":\"" + "x".repeat(2001) + "\",\"clarificationQuestion\":null,\"reasonCode\":\"RESOLVED_REFERENCES\"}",
                "{\"status\":\"unchanged\",\"resolvedQuestion\":\"Question?\",\"clarificationQuestion\":null,\"reasonCode\":\"STANDALONE\"} {}",
                "{\"status\":\"rewritten\",\"resolvedQuestion\":\"Question?\",\"clarificationQuestion\":null,\"reasonCode\":\"RESOLVED_REFERENCES\",\"tenantId\":\"other\"}");
    }

    @Test
    void handlesRefusalButRejectsIncompleteMixedAndToolOutputs() {
        var refusal = mapper.createObjectNode().put("status", "completed");
        refusal.putArray("output").addObject().put("type", "message").put("role", "assistant")
                .putArray("content").addObject().put("type", "refusal").put("refusal", "private-secret");
        when(client.post(anyString(), anyMap(), any(), eq(false))).thenReturn(Mono.just(refusal));
        assertThat(resolver.resolve("Question?", history).block()).isEqualTo(QuestionResolution.refused());
        for (String type : List.of("function_call", "unknown")) {
            var invalid = mapper.createObjectNode().put("status", "completed"); invalid.putArray("output").addObject().put("type", type);
            when(client.post(anyString(), anyMap(), any(), eq(false))).thenReturn(Mono.just(invalid));
            StepVerifier.create(resolver.resolve("Question?", history)).expectError(OperationException.class).verify();
        }
        refusal.put("status", "incomplete");
        when(client.post(anyString(), anyMap(), any(), eq(false))).thenReturn(Mono.just(refusal));
        StepVerifier.create(resolver.resolve("Question?", history)).expectError(OperationException.class).verify();
    }

    @Test
    void sanitizesTransportErrorsAndValidatesTimeout() {
        for (String code : List.of("MODEL_TIMEOUT", "MODEL_RATE_LIMITED", "MODEL_REQUEST_REJECTED")) {
            when(client.post(anyString(), anyMap(), any(), eq(false))).thenReturn(Mono.error(new OperationException(HttpStatus.BAD_GATEWAY, code, "private-secret")));
            StepVerifier.create(resolver.resolve("Question?", history)).expectErrorSatisfies(error -> {
                assertThat(error.getMessage()).doesNotContain("private-secret");
                assertThat(((OperationException) error).code()).isEqualTo(switch (code) {
                    case "MODEL_TIMEOUT" -> "QUERY_REWRITE_TIMEOUT";
                    case "MODEL_REQUEST_REJECTED" -> "QUERY_REWRITE_REJECTED";
                    default -> "QUERY_REWRITE_UNAVAILABLE";
                });
            }).verify();
        }
        for (Duration invalid : List.of(Duration.ZERO, Duration.ofSeconds(-1), Duration.ofSeconds(61))) {
            assertThatThrownBy(() -> properties.setRewriteTimeout(invalid)).isInstanceOf(IllegalArgumentException.class);
        }
    }

    private JsonNode envelope(String text) {
        return mapper.valueToTree(Map.of("status", "completed", "output", List.of(Map.of("type", "message", "role", "assistant",
                "content", List.of(Map.of("type", "output_text", "text", text))))));
    }
}
