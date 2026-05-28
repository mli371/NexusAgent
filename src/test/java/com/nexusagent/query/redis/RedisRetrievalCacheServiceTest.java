package com.nexusagent.query.redis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexusagent.context.application.ContextProperties;
import com.nexusagent.context.domain.ContextBuildResult;
import com.nexusagent.context.domain.ContextDebugMetadata;
import com.nexusagent.query.application.QueryCacheKey;
import com.nexusagent.retrieval.application.RetrievalProperties;
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
class RedisRetrievalCacheServiceTest {

    @Mock
    ReactiveStringRedisTemplate redisTemplate;

    @Mock
    ReactiveValueOperations<String, String> valueOperations;

    ObjectMapper objectMapper;
    NexusRedisProperties redisProperties;
    RetrievalProperties retrievalProperties;
    ContextProperties contextProperties;
    RedisKeyFactory keyFactory;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper().findAndRegisterModules();
        redisProperties = new NexusRedisProperties();
        retrievalProperties = new RetrievalProperties();
        contextProperties = new ContextProperties();
        keyFactory = new RedisKeyFactory();
        lenient().when(redisTemplate.opsForValue()).thenReturn(valueOperations);
    }

    @Test
    void cacheMissReturnsEmpty() {
        RedisRetrievalCacheService cache = service();
        when(valueOperations.get(anyString())).thenReturn(Mono.empty());

        StepVerifier.create(cache.get(key()))
                .verifyComplete();
    }

    @Test
    void writesAndReadsSerializedContextWithSchemaVersion() {
        RedisRetrievalCacheService cache = service();
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

        StepVerifier.create(cache.put(key(), context()))
                .verifyComplete();
        assertThat(storedKey.get()).startsWith("retrieval:");
        assertThat(storedKey.get()).endsWith(":candidates");
        assertThat(storedTtl.get()).isEqualTo(redisProperties.getRetrievalCacheTtl());
        assertThat(storedTtl.get()).isGreaterThan(Duration.ZERO);
        assertThat(storedJson.get()).contains("\"schemaVersion\":1");

        StepVerifier.create(cache.get(key()))
                .assertNext(result -> {
                    assertThat(result.query()).isEqualTo("security policy");
                    assertThat(result.finalContextText()).contains("Security policy");
                })
                .verifyComplete();
    }

    @Test
    void skipsOversizedContextEntries() {
        redisProperties.setMaxCacheEntryBytes(20);
        RedisRetrievalCacheService cache = service();

        StepVerifier.create(cache.put(key(), context()))
                .verifyComplete();

        verify(valueOperations, never()).set(anyString(), anyString(), any(Duration.class));
    }

    @Test
    void redisUnavailableFallsBackToCacheMissAndNoOpWrite() {
        RedisRetrievalCacheService cache = service();
        when(valueOperations.get(anyString())).thenReturn(Mono.error(new IllegalStateException("redis down")));
        when(valueOperations.set(anyString(), anyString(), any(Duration.class)))
                .thenReturn(Mono.error(new IllegalStateException("redis down")));

        StepVerifier.create(cache.get(key()))
                .verifyComplete();
        StepVerifier.create(cache.put(key(), context()))
                .verifyComplete();
    }

    private RedisRetrievalCacheService service() {
        return new RedisRetrievalCacheService(
                redisTemplate,
                objectMapper,
                redisProperties,
                retrievalProperties,
                contextProperties,
                keyFactory
        );
    }

    private QueryCacheKey key() {
        return new QueryCacheKey("security policy", List.of(), 5, 1000);
    }

    private ContextBuildResult context() {
        return new ContextBuildResult(
                "security policy",
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                "[C1] policy.txt\nSecurity policy access controls.",
                new ContextDebugMetadata("deterministic-heuristic", 1, 1, 1, 1, 1000, 40, 0, 0)
        );
    }
}
