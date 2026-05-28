package com.nexusagent.query.redis;

import java.nio.charset.StandardCharsets;
import java.time.Instant;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexusagent.query.application.ToolOutputStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

@Service
@ConditionalOnProperty(prefix = "nexus.redis", name = "enabled", havingValue = "true")
public class RedisToolOutputStore implements ToolOutputStore {

    private static final Logger log = LoggerFactory.getLogger(RedisToolOutputStore.class);
    private static final int SCHEMA_VERSION = 1;

    private final ReactiveStringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
    private final NexusRedisProperties redisProperties;
    private final RedisKeyFactory keyFactory;

    public RedisToolOutputStore(
            ReactiveStringRedisTemplate redisTemplate,
            ObjectMapper objectMapper,
            NexusRedisProperties redisProperties,
            RedisKeyFactory keyFactory
    ) {
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
        this.redisProperties = redisProperties;
        this.keyFactory = keyFactory;
    }

    @Override
    public Mono<Void> save(String sessionOrTraceId, String key, Object value) {
        if (isBlank(sessionOrTraceId) || isBlank(key)) {
            return Mono.empty();
        }
        String redisKey = keyFactory.toolResult(sessionOrTraceId, key);
        return Mono.fromCallable(() -> serialize(value))
                .flatMap(json -> {
                    int bytes = json.getBytes(StandardCharsets.UTF_8).length;
                    if (bytes > redisProperties.getMaxToolOutputBytes()) {
                        log.info(
                                "Skipping Redis tool output write for key {} because value is {} bytes, above {} byte limit",
                                redisKey,
                                bytes,
                                redisProperties.getMaxToolOutputBytes()
                        );
                        return Mono.empty();
                    }
                    return redisTemplate.opsForValue()
                            .set(redisKey, json, redisProperties.getToolOutputTtl())
                            .then();
                })
                .onErrorResume(error -> {
                    log.warn("Redis tool output write failed for key {}: {}", redisKey, error.getMessage());
                    return Mono.empty();
                });
    }

    @Override
    public Mono<Object> get(String sessionOrTraceId, String key) {
        if (isBlank(sessionOrTraceId) || isBlank(key)) {
            return Mono.empty();
        }
        String redisKey = keyFactory.toolResult(sessionOrTraceId, key);
        return redisTemplate.opsForValue()
                .get(redisKey)
                .flatMap(this::deserialize)
                .filter(envelope -> envelope.schemaVersion() == SCHEMA_VERSION)
                .map(envelope -> (Object) envelope.value())
                .onErrorResume(error -> {
                    log.warn("Redis tool output read failed for key {}: {}", redisKey, error.getMessage());
                    return Mono.empty();
                });
    }

    private String serialize(Object value) throws JsonProcessingException {
        ToolOutputEnvelope envelope = new ToolOutputEnvelope(
                SCHEMA_VERSION,
                Instant.now(),
                value == null ? "null" : value.getClass().getName(),
                objectMapper.valueToTree(value)
        );
        return objectMapper.writeValueAsString(envelope);
    }

    private Mono<ToolOutputEnvelope> deserialize(String json) {
        try {
            return Mono.just(objectMapper.readValue(json, ToolOutputEnvelope.class));
        } catch (JsonProcessingException exception) {
            return Mono.error(exception);
        }
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    record ToolOutputEnvelope(
            int schemaVersion,
            Instant savedAt,
            String valueType,
            JsonNode value
    ) {
    }
}
