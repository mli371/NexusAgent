package com.nexusagent.query.live;

import static org.assertj.core.api.Assertions.*;

import java.time.Duration;
import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexusagent.query.application.QueryProperties;
import org.junit.jupiter.api.*;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import reactor.core.publisher.Flux;

@Testcontainers(disabledWithoutDocker = true)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class RedisSemanticContextCacheTest {
    @Container static final GenericContainer<?> container = new GenericContainer<>(DockerImageName.parse("redis:7.4-alpine")).withExposedPorts(6379);
    static LettuceConnectionFactory connection;
    static ReactiveStringRedisTemplate redis;
    final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    final QueryProperties properties = new QueryProperties();
    final String scope = LiveContextCacheKey.hash(UUID.randomUUID().toString());
    RedisSemanticContextCache cache;

    @BeforeAll static void connect() {
        connection = new LettuceConnectionFactory(container.getHost(), container.getMappedPort(6379));
        connection.afterPropertiesSet(); connection.start();
        redis = new ReactiveStringRedisTemplate(connection);
    }
    @AfterAll static void close() { connection.destroy(); }
    @BeforeEach void setup() { cache = new RedisSemanticContextCache(redis, mapper, properties); }

    @Test void roundTripsBoundedMetadataWithPositiveTtlAndNoQuestionOrContextText() throws Exception {
        var source = source("one", Duration.ofSeconds(20));
        cache.put(source).block();
        var read = cache.read(scope, 3).block();
        assertThat(read.sources()).containsExactly(source);
        var ttl = redis.getExpire(RedisSemanticContextCache.key(scope)).block();
        // Docker's wall clock can differ slightly; the stored absolute deadline must still be exact.
        assertThat(ttl).isPositive().isLessThanOrEqualTo(Duration.ofSeconds(20).plusMillis(100));
        assertThat(absoluteExpiry()).isEqualTo(source.expiresAt().toEpochMilli());
        cache.read(scope, 3).block();
        assertThat(absoluteExpiry()).isEqualTo(source.expiresAt().toEpochMilli());
        var json = mapper.readTree(mapper.writeValueAsString(read.sources().get(0)));
        assertThat(json.has("question")).isFalse(); assertThat(json.has("context")).isFalse(); assertThat(json.has("answer")).isFalse();
        assertThat(cache.read(LiveContextCacheKey.hash("other"), 3).block().sources()).isEmpty();
        assertThat(cache.read(scope, 384).block().sources()).isEmpty();
    }

    @Test void concurrentWritesCannotExceedScopeCapacity() {
        properties.setSemanticCacheMaxEntries(5);
        Flux.range(0, 40).flatMap(i -> cache.put(source("q" + i, Duration.ofSeconds(20))), 8).then().block();
        assertThat(redis.opsForZSet().size(RedisSemanticContextCache.key(scope)).block()).isEqualTo(5);
        assertThat(cache.read(scope, 3).block().sources()).hasSize(5);
        assertThat(redis.getExpire(RedisSemanticContextCache.key(scope)).block()).isPositive();
    }

    @Test void aNewSourceDoesNotExtendAnOlderMembersExpiry() throws Exception {
        var old = source("old", Duration.ofMillis(700));
        cache.put(old).block(); cache.put(source("new", Duration.ofSeconds(20))).block();
        Thread.sleep(900);
        assertThat(redis.getExpire(RedisSemanticContextCache.key(scope)).block()).isPositive();
        assertThat(cache.read(scope, 3).block().sources()).hasSize(1)
                .noneMatch(s -> s.questionHash().equals(old.questionHash()));
        cache.put(source("newer", Duration.ofSeconds(20))).block();
        assertThat(redis.opsForZSet().size(RedisSemanticContextCache.key(scope)).block()).isEqualTo(2);
    }

    @Test void corruptForeignAndOversizedMetadataIsIgnored() throws Exception {
        var good = source("good", Duration.ofSeconds(20));
        var foreign = new SemanticContextCache.Source(1, LiveContextCacheKey.hash("foreign"), good.questionHash(),
                good.vector(), good.features(), good.cacheKey(), good.fingerprint(), good.createdAt(), good.expiresAt());
        var wrongVersion = new SemanticContextCache.Source(99, scope, good.questionHash(), good.vector(), good.features(),
                good.cacheKey(), good.fingerprint(), good.createdAt(), good.expiresAt());
        for (String value : List.of("private-corrupt", "x".repeat(RedisSemanticContextCache.MAX_SOURCE_BYTES + 1),
                mapper.writeValueAsString(foreign), mapper.writeValueAsString(wrongVersion))) {
            redis.opsForZSet().add(RedisSemanticContextCache.key(scope), value, good.expiresAt().toEpochMilli()).block();
        }
        cache.put(good).block();
        assertThat(cache.read(scope, 3).block().sources()).containsExactly(good);
    }

    @Test void oversizedAndInvalidVectorWritesAreSkipped() {
        var original = source("large", Duration.ofSeconds(20));
        var huge = new SemanticContextCache.Source(1, scope, original.questionHash(), Collections.nCopies(10_000, 1f),
                original.features(), original.cacheKey(), original.fingerprint(), original.createdAt(), original.expiresAt());
        cache.put(huge).block();
        var invalid = new SemanticContextCache.Source(1, scope, original.questionHash(), List.of(0f, 0f, 0f),
                original.features(), original.cacheKey(), original.fingerprint(), original.createdAt(), original.expiresAt());
        cache.put(invalid).block();
        assertThat(redis.hasKey(RedisSemanticContextCache.key(scope)).block()).isFalse();
    }

    @Test @Order(Integer.MAX_VALUE) void unavailableRedisBecomesMissAndWriteIsIgnored() {
        container.stop();
        assertThat(cache.read(scope, 3).block().reason()).isEqualTo("unavailable");
        cache.put(source("offline", Duration.ofSeconds(20))).block();
    }

    private SemanticContextCache.Source source(String question, Duration life) {
        Instant now = Instant.now();
        return new SemanticContextCache.Source(1, scope, LiveContextCacheKey.hash(question), List.of(1f, 0f, 0f),
                new SemanticReusePolicy().features("What is the policy?").orElseThrow(),
                "retrieval:live-context-v1:" + LiveContextCacheKey.hash(question) + ":context",
                LiveContextCacheKey.hash("context"), now, now.plus(life));
    }

    private long absoluteExpiry() {
        return redis.execute(new org.springframework.data.redis.core.script.DefaultRedisScript<>(
                "return redis.call('PEXPIRETIME', KEYS[1])", Long.class), List.of(RedisSemanticContextCache.key(scope)))
                .single().block();
    }
}
