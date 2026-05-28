package com.nexusagent.query.redis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.data.redis.core.ReactiveValueOperations;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

@ExtendWith(MockitoExtension.class)
class RedisToolOutputStoreTest {

    @Mock
    ReactiveStringRedisTemplate redisTemplate;

    @Mock
    ReactiveValueOperations<String, String> valueOperations;

    ObjectMapper objectMapper;
    NexusRedisProperties redisProperties;
    RedisKeyFactory keyFactory;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper().findAndRegisterModules();
        redisProperties = new NexusRedisProperties();
        keyFactory = new RedisKeyFactory();
        lenient().when(redisTemplate.opsForValue()).thenReturn(valueOperations);
    }

    @Test
    void writesAndReadsToolOutputAsJsonEnvelope() {
        RedisToolOutputStore store = service();
        AtomicReference<String> storedJson = new AtomicReference<>();
        AtomicReference<String> storedKey = new AtomicReference<>();
        AtomicReference<Duration> storedTtl = new AtomicReference<>();
        when(valueOperations.set(anyString(), anyString(), any(Duration.class))).thenAnswer(invocation -> {
            storedKey.set(invocation.getArgument(0));
            storedJson.set(invocation.getArgument(1));
            storedTtl.set(invocation.getArgument(2));
            return Mono.just(true);
        });
        when(valueOperations.get(anyString())).thenAnswer(invocation ->
                storedJson.get() == null ? Mono.empty() : Mono.just(storedJson.get()));

        StepVerifier.create(store.save("s1", "answer", Map.of("value", "ok")))
                .verifyComplete();
        assertThat(storedKey.get()).isEqualTo("tool:s1:answer:result");
        assertThat(storedTtl.get()).isEqualTo(redisProperties.getToolOutputTtl());
        assertThat(storedTtl.get()).isGreaterThan(Duration.ZERO);
        assertThat(storedJson.get()).contains("\"schemaVersion\":1");

        StepVerifier.create(store.get("s1", "answer"))
                .assertNext(value -> {
                    assertThat(value).isInstanceOf(JsonNode.class);
                    assertThat(((JsonNode) value).get("value").asText()).isEqualTo("ok");
                })
                .verifyComplete();
    }

    @Test
    void writesAgentWorkflowOutputWithTtlAndSchemaVersion() {
        RedisToolOutputStore store = service();
        AtomicReference<String> storedJson = new AtomicReference<>();
        AtomicReference<String> storedKey = new AtomicReference<>();
        AtomicReference<Duration> storedTtl = new AtomicReference<>();
        when(valueOperations.set(anyString(), anyString(), any(Duration.class))).thenAnswer(invocation -> {
            storedKey.set(invocation.getArgument(0));
            storedJson.set(invocation.getArgument(1));
            storedTtl.set(invocation.getArgument(2));
            return Mono.just(true);
        });

        StepVerifier.create(store.save("s1", "agent-plan", Map.of("plannerName", "deterministic-rule-planner")))
                .verifyComplete();

        assertThat(storedKey.get()).isEqualTo("tool:s1:agent-plan:result");
        assertThat(storedTtl.get()).isEqualTo(redisProperties.getToolOutputTtl());
        assertThat(storedTtl.get()).isGreaterThan(Duration.ZERO);
        assertThat(storedJson.get()).contains("\"schemaVersion\":1");
        assertThat(storedJson.get()).contains("deterministic-rule-planner");
    }

    @Test
    void skipsOversizedToolOutputsSoLargeRawPayloadsOrContextBlobsAreNotStored() {
        redisProperties.setMaxToolOutputBytes(20);
        RedisToolOutputStore store = service();

        StepVerifier.create(store.save("s1", "agent-execution", "x".repeat(1_000)))
                .verifyComplete();

        verify(valueOperations, never()).set(anyString(), anyString(), any(Duration.class));
    }

    @Test
    void redisUnavailableFallsBackToEmptyReadAndNoOpWrite() {
        RedisToolOutputStore store = service();
        when(valueOperations.get(anyString())).thenReturn(Mono.error(new IllegalStateException("redis down")));
        when(valueOperations.set(anyString(), anyString(), any(Duration.class)))
                .thenReturn(Mono.error(new IllegalStateException("redis down")));

        StepVerifier.create(store.get("s1", "answer"))
                .verifyComplete();
        StepVerifier.create(store.save("s1", "answer", Map.of("value", "ok")))
                .verifyComplete();
    }

    private RedisToolOutputStore service() {
        return new RedisToolOutputStore(redisTemplate, objectMapper, redisProperties, keyFactory);
    }
}
