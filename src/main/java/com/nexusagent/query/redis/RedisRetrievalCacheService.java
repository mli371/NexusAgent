package com.nexusagent.query.redis;

import java.nio.charset.StandardCharsets;
import java.time.Instant;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexusagent.context.application.ContextProperties;
import com.nexusagent.context.domain.ContextBuildResult;
import com.nexusagent.query.application.QueryCacheKey;
import com.nexusagent.query.application.RetrievalCacheService;
import com.nexusagent.retrieval.application.RetrievalProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

@Service
@ConditionalOnProperty(prefix = "nexus.redis", name = "enabled", havingValue = "true")
public class RedisRetrievalCacheService implements RetrievalCacheService {

    private static final Logger log = LoggerFactory.getLogger(RedisRetrievalCacheService.class);
    private static final int SCHEMA_VERSION = 1;

    private final ReactiveStringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
    private final NexusRedisProperties redisProperties;
    private final RetrievalProperties retrievalProperties;
    private final ContextProperties contextProperties;
    private final RedisKeyFactory keyFactory;

    public RedisRetrievalCacheService(
            ReactiveStringRedisTemplate redisTemplate,
            ObjectMapper objectMapper,
            NexusRedisProperties redisProperties,
            RetrievalProperties retrievalProperties,
            ContextProperties contextProperties,
            RedisKeyFactory keyFactory
    ) {
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
        this.redisProperties = redisProperties;
        this.retrievalProperties = retrievalProperties;
        this.contextProperties = contextProperties;
        this.keyFactory = keyFactory;
    }

    @Override
    public Mono<ContextBuildResult> get(QueryCacheKey key) {
        String redisKey = keyFactory.retrievalCandidates(key, retrievalProperties, contextProperties);
        return redisTemplate.opsForValue()
                .get(redisKey)
                .flatMap(this::deserialize)
                .filter(envelope -> envelope.schemaVersion() == SCHEMA_VERSION)
                .map(CachedContextEnvelope::context)
                .doOnNext(ignored -> log.debug("Redis retrieval cache hit for key {}", redisKey))
                .switchIfEmpty(Mono.defer(() -> {
                    log.debug("Redis retrieval cache miss for key {}", redisKey);
                    return Mono.empty();
                }))
                .onErrorResume(error -> {
                    log.warn("Redis retrieval cache read failed for key {}: {}", redisKey, error.getMessage());
                    return Mono.empty();
                });
    }

    @Override
    public Mono<Void> put(QueryCacheKey key, ContextBuildResult value) {
        String redisKey = keyFactory.retrievalCandidates(key, retrievalProperties, contextProperties);
        return Mono.fromCallable(() -> serialize(new CachedContextEnvelope(SCHEMA_VERSION, Instant.now(), value)))
                .flatMap(json -> {
                    int bytes = json.getBytes(StandardCharsets.UTF_8).length;
                    if (bytes > redisProperties.getMaxCacheEntryBytes()) {
                        log.info(
                                "Skipping Redis retrieval cache write for key {} because value is {} bytes, above {} byte limit",
                                redisKey,
                                bytes,
                                redisProperties.getMaxCacheEntryBytes()
                        );
                        return Mono.empty();
                    }
                    return redisTemplate.opsForValue()
                            .set(redisKey, json, redisProperties.getRetrievalCacheTtl())
                            .then();
                })
                .onErrorResume(error -> {
                    log.warn("Redis retrieval cache write failed for key {}: {}", redisKey, error.getMessage());
                    return Mono.empty();
                });
    }

    private String serialize(CachedContextEnvelope envelope) throws JsonProcessingException {
        return objectMapper.writeValueAsString(envelope);
    }

    private Mono<CachedContextEnvelope> deserialize(String json) {
        try {
            return Mono.just(objectMapper.readValue(json, CachedContextEnvelope.class));
        } catch (JsonProcessingException exception) {
            return Mono.error(exception);
        }
    }

    record CachedContextEnvelope(
            int schemaVersion,
            Instant cachedAt,
            ContextBuildResult context
    ) {
    }
}
