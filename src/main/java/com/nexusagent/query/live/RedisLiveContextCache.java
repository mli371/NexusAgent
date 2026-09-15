package com.nexusagent.query.live;

import java.nio.charset.StandardCharsets;
import java.time.Instant;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexusagent.context.domain.ContextBuildResult;
import com.nexusagent.query.application.QueryProperties;
import com.nexusagent.retrieval.domain.HybridRetrievalResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import reactor.core.publisher.Mono;

final class RedisLiveContextCache implements LiveContextCache {
    private static final Logger log = LoggerFactory.getLogger(RedisLiveContextCache.class);
    private final ReactiveStringRedisTemplate redis;
    private final ObjectMapper mapper;
    private final QueryProperties properties;

    RedisLiveContextCache(ReactiveStringRedisTemplate redis, ObjectMapper mapper, QueryProperties properties) {
        this.redis = redis;
        this.mapper = mapper;
        this.properties = properties;
    }

    public boolean enabled() { return true; }

    public Mono<Lookup> get(String key) {
        return Mono.defer(() -> redis.opsForValue().get(key))
                .map(json -> decode(key, json)).defaultIfEmpty(new Lookup(null, "not_found"))
                .timeout(properties.getLiveCacheTimeout())
                .onErrorResume(error -> {
                    log.warn("live_context_cache_read_failed key={} errorType={}", key, error.getClass().getSimpleName());
                    return Mono.just(new Lookup(null, "unavailable"));
                });
    }

    private Lookup decode(String key, String json) {
        try {
            if (json.getBytes(StandardCharsets.UTF_8).length > properties.getLiveCacheMaxBytes()) {
                return new Lookup(null, "oversized");
            }
            Envelope value = mapper.readerFor(Envelope.class)
                    .with(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS).readValue(json);
            if (value.schemaVersion() != 2 || !key.equals(value.cacheKey()) || value.cachedAt() == null
                    || value.cachedAt().isAfter(Instant.now())
                    || value.cachedAt().plus(properties.getLiveCacheTtl()).isBefore(Instant.now())
                    || value.context() == null) { return new Lookup(null, "invalid_entry"); }
            return new Lookup(value.context(), "found", fingerprint(value.context()), value.cachedAt().plus(properties.getLiveCacheTtl()));
        } catch (Exception ignored) {
            // Jackson messages may include evidence text. Never log the exception or raw JSON.
            return new Lookup(null, "invalid_entry");
        }
    }

    public Mono<Void> put(String key, ContextBuildResult context) {
        return putWithReceipt(key, context).then();
    }

    public Mono<Stored> putWithReceipt(String key, ContextBuildResult context) {
        return Mono.<Stored>defer(() -> {
            if (context.finalContextText().isBlank() || context.citations().isEmpty()) { return Mono.empty(); }
            Instant createdAt = Instant.now();
            return Mono.fromCallable(() -> mapper.writeValueAsString(new Envelope(2, key, createdAt, withQuery(context, ""))))
                    .flatMap(json -> {
                        int bytes = json.getBytes(StandardCharsets.UTF_8).length;
                        if (bytes > properties.getLiveCacheMaxBytes()) {
                            log.info("live_context_cache_write_skipped key={} reason=oversized bytes={}", key, bytes);
                            return Mono.empty();
                        }
                        return redis.opsForValue().set(key, json, properties.getLiveCacheTtl())
                                .filter(Boolean.TRUE::equals)
                                .flatMap(ignored -> Mono.fromCallable(() -> new Stored(key, fingerprint(context),
                                        createdAt.plus(properties.getLiveCacheTtl()))));
                    });
        }).timeout(properties.getLiveCacheTimeout()).onErrorResume(error -> {
            log.warn("live_context_cache_write_failed key={} errorType={}", key, error.getClass().getSimpleName());
            return Mono.empty();
        });
    }

    private String fingerprint(ContextBuildResult context) throws com.fasterxml.jackson.core.JsonProcessingException {
        return LiveContextCacheKey.hash(mapper.writer().with(com.fasterxml.jackson.databind.SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
                .writeValueAsBytes(withQuery(context, "")));
    }

    static ContextBuildResult withQuery(ContextBuildResult context, String query) {
        var retrieval = context.retrievalResult();
        return new ContextBuildResult(query, context.rerankedCandidates(), context.selectedChildChunks(),
                context.expandedParentContexts(), context.citations(), context.finalContextText(), context.debugMetadata(),
                retrieval == null ? null : new HybridRetrievalResult(query, retrieval.vectorCandidates(),
                        retrieval.fullTextCandidates(), retrieval.fusedCandidates()));
    }

    record Envelope(int schemaVersion, String cacheKey, Instant cachedAt, ContextBuildResult context) { }
}
