package com.nexusagent.query.live;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexusagent.query.application.QueryProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.domain.Range;
import org.springframework.data.redis.connection.Limit;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import reactor.core.publisher.Mono;

final class RedisSemanticContextCache implements SemanticContextCache {
    static final int MAX_SOURCE_BYTES = 16 * 1024;
    private static final Logger log = LoggerFactory.getLogger(RedisSemanticContextCache.class);
    private static final DefaultRedisScript<Long> WRITE = writeScript();
    private final ReactiveStringRedisTemplate redis;
    private final ObjectMapper mapper;
    private final QueryProperties properties;

    RedisSemanticContextCache(ReactiveStringRedisTemplate redis, ObjectMapper mapper, QueryProperties properties) {
        this.redis = redis; this.mapper = mapper; this.properties = properties;
    }

    public boolean enabled() { return true; }
    private static DefaultRedisScript<Long> writeScript() {
        var script = new DefaultRedisScript<Long>();
        script.setLocation(new ClassPathResource("redis/semantic-context-put.lua"));
        script.setResultType(Long.class);
        script.getSha1(); // Load the small classpath resource at initialization, not on a request.
        return script;
    }
    static String key(String scopeHash) { return "retrieval:semantic-context-v1:{" + scopeHash + "}:candidates"; }

    public Mono<Read> read(String scopeHash, int dimension) {
        return Mono.defer(() -> redis.opsForZSet().rangeByScore(key(scopeHash),
                        Range.rightUnbounded(Range.Bound.exclusive((double) Instant.now().toEpochMilli())),
                        Limit.limit().count(properties.getSemanticCacheMaxEntries()))
                .take(properties.getSemanticCacheMaxEntries())
                .flatMap(json -> Mono.justOrEmpty(decode(json, scopeHash, dimension))).collectList()
                .map(sources -> new Read(List.copyOf(sources), sources.isEmpty() ? "not_found" : "found")))
                .timeout(properties.getLiveCacheTimeout()).onErrorResume(error -> {
                    log.warn("semantic_cache_read_failed errorType={}", error.getClass().getSimpleName());
                    return Mono.just(new Read(List.of(), "unavailable"));
                });
    }

    private Optional<Source> decode(String json, String scope, int dimension) {
        try {
            if (json.getBytes(StandardCharsets.UTF_8).length > MAX_SOURCE_BYTES) { return Optional.empty(); }
            Source value = mapper.readerFor(Source.class).with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).readValue(json);
            return valid(value, scope, dimension, Instant.now()) ? Optional.of(value) : Optional.empty();
        } catch (Exception ignored) { return Optional.empty(); }
    }

    private boolean valid(Source source, String scope, int dimension, Instant now) {
        return source != null && source.schemaVersion() == 1 && scope.equals(source.scopeHash()) && hex(scope)
                && hex(source.questionHash()) && hex(source.fingerprint()) && source.features() != null
                && source.features().intent() != null && hex(source.features().constraintHash())
                && source.cacheKey() != null && source.cacheKey().matches("retrieval:live-context-v1:[a-f0-9]{64}:context")
                && source.createdAt() != null && !source.createdAt().isAfter(now) && source.expiresAt() != null
                && source.expiresAt().isAfter(now)
                && !source.expiresAt().isAfter(source.createdAt().plus(properties.getLiveCacheTtl()))
                && SemanticReusePolicy.validVector(source.vector(), dimension);
    }

    private boolean hex(String value) { return value != null && value.matches("[a-f0-9]{64}"); }

    public Mono<Void> put(Source source) {
        return Mono.defer(() -> {
            Instant now = Instant.now();
            if (source == null || source.vector() == null || !valid(source, source.scopeHash(), source.vector().size(), now)) {
                return Mono.empty();
            }
            return Mono.fromCallable(() -> mapper.writeValueAsString(source)).flatMap(json -> {
                if (json.getBytes(StandardCharsets.UTF_8).length > MAX_SOURCE_BYTES) {
                    log.info("semantic_cache_write_skipped reason=oversized");
                    return Mono.empty();
                }
                return redis.execute(WRITE, List.of(key(source.scopeHash())), List.of(json,
                        Long.toString(now.toEpochMilli()), Long.toString(source.expiresAt().toEpochMilli()),
                        Integer.toString(properties.getSemanticCacheMaxEntries()))).then();
            });
        }).timeout(properties.getLiveCacheTimeout()).onErrorResume(error -> {
            log.warn("semantic_cache_write_failed errorType={}", error.getClass().getSimpleName());
            return Mono.empty();
        });
    }
}
