package com.nexusagent.query.redis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.ReactiveListOperations;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.data.redis.core.ReactiveValueOperations;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

@ExtendWith(MockitoExtension.class)
class RedisSessionStateServiceTest {

    @Mock
    ReactiveStringRedisTemplate redisTemplate;

    @Mock
    ReactiveListOperations<String, String> listOperations;

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
        lenient().when(redisTemplate.opsForList()).thenReturn(listOperations);
        lenient().when(redisTemplate.opsForValue()).thenReturn(valueOperations);
    }

    @Test
    void writesAndReadsRecentSessionSummaryAndQueryStatus() {
        RedisSessionStateService service = service();
        List<String> recentEvents = new ArrayList<>();
        Map<String, String> values = new HashMap<>();
        Map<String, Duration> valueTtls = new HashMap<>();
        Map<String, Duration> expireTtls = new HashMap<>();

        when(listOperations.leftPush(anyString(), anyString())).thenAnswer(invocation -> {
            recentEvents.add(0, invocation.getArgument(1));
            return Mono.just((long) recentEvents.size());
        });
        when(listOperations.trim(anyString(), anyLong(), anyLong())).thenReturn(Mono.just(true));
        when(listOperations.range(anyString(), anyLong(), anyLong())).thenReturn(Flux.defer(() -> Flux.fromIterable(recentEvents)));
        when(redisTemplate.expire(anyString(), any(Duration.class))).thenAnswer(invocation -> {
            expireTtls.put(invocation.getArgument(0), invocation.getArgument(1));
            return Mono.just(true);
        });
        when(valueOperations.set(anyString(), anyString(), any(Duration.class))).thenAnswer(invocation -> {
            values.put(invocation.getArgument(0), invocation.getArgument(1));
            valueTtls.put(invocation.getArgument(0), invocation.getArgument(2));
            return Mono.just(true);
        });
        when(valueOperations.get(anyString())).thenAnswer(invocation -> Mono.justOrEmpty(values.get(invocation.getArgument(0))));

        StepVerifier.create(service.recordStarted("s1", "trace-1", "security policy")
                        .then(service.recordCompleted("s1", "trace-1")))
                .verifyComplete();

        assertThat(expireTtls).containsKey("session:s1:recent");
        assertThat(expireTtls.get("session:s1:recent")).isEqualTo(redisProperties.getRecentSessionTtl());
        assertThat(expireTtls.get("session:s1:recent")).isGreaterThan(Duration.ZERO);
        assertThat(valueTtls).containsKeys("session:s1:summary", "query:trace-1:status");
        assertThat(valueTtls.get("session:s1:summary")).isEqualTo(redisProperties.getSessionSummaryTtl());
        assertThat(valueTtls.get("query:trace-1:status")).isEqualTo(redisProperties.getQueryStatusTtl());
        assertThat(valueTtls.get("session:s1:summary")).isGreaterThan(Duration.ZERO);
        assertThat(valueTtls.get("query:trace-1:status")).isGreaterThan(Duration.ZERO);

        StepVerifier.create(service.recentEvents("s1"))
                .assertNext(events -> {
                    assertThat(events).hasSize(2);
                    assertThat(events).extracting(RedisSessionStateService.SessionEventEnvelope::status)
                            .containsExactly("completed", "started");
                    assertThat(events).allSatisfy(event -> assertThat(event.schemaVersion()).isEqualTo(1));
                })
                .verifyComplete();
        StepVerifier.create(service.summary("s1"))
                .assertNext(summary -> {
                    assertThat(summary.lastStatus()).isEqualTo("completed");
                    assertThat(summary.schemaVersion()).isEqualTo(1);
                })
                .verifyComplete();
        StepVerifier.create(service.queryStatus("trace-1"))
                .assertNext(status -> assertThat(status.status()).isEqualTo("completed"))
                .verifyComplete();
    }

    @Test
    void redisUnavailableDoesNotFailSessionWrite() {
        RedisSessionStateService service = service();
        when(valueOperations.set(anyString(), anyString(), any(Duration.class)))
                .thenReturn(Mono.error(new IllegalStateException("redis down")));

        StepVerifier.create(service.recordStarted(null, "trace-1", "security policy"))
                .verifyComplete();
    }

    private RedisSessionStateService service() {
        return new RedisSessionStateService(redisTemplate, objectMapper, redisProperties, keyFactory);
    }
}
