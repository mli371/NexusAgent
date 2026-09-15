package com.nexusagent.query.live;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import com.nexusagent.chunking.domain.*;
import com.nexusagent.chunking.repository.ChunkRepository;
import com.nexusagent.common.context.RequestContext;
import com.nexusagent.documents.domain.DocumentMetadata;
import com.nexusagent.documents.domain.DocumentVisibility;
import com.nexusagent.documents.repository.DocumentRepository;
import com.nexusagent.embeddings.domain.EmbeddingModelInfo;
import com.nexusagent.embeddings.repository.ChildChunkEmbeddingRepository;
import com.nexusagent.query.api.QueryRequest;
import com.nexusagent.query.api.QueryStreamEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.transaction.ReactiveTransactionManager;
import org.springframework.transaction.reactive.TransactionalOperator;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import reactor.core.publisher.Mono;

@SpringBootTest(properties = {"nexus.redis.enabled=true", "nexus.agent.enabled=false", "nexus.query.live-cache-enabled=true",
        "nexus.query.answer-provider=openai", "nexus.embeddings.provider=openai", "nexus.embeddings.dimension=384",
        "nexus.embeddings.model=text-embedding-3-small", "nexus.openai.api-key=test-key-not-real",
        "nexus.openai.answer-model=gpt-5.6-luna", "spring.config.import="})
@Import(LiveQueryIntegrationTest.ModelFixture.class)
@Testcontainers(disabledWithoutDocker = true)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class LiveContextCacheIntegrationTest {
    static final EmbeddingModelInfo MODEL = new EmbeddingModelInfo("openai", "text-embedding-3-small", 384);
    static final String TEXT = "Synthetic security policy requires approval before accessing confidential documents.";
    @Container static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(DockerImageName
            .parse("pgvector/pgvector:pg16").asCompatibleSubstituteFor("postgres"));
    @Container static final GenericContainer<?> redisContainer = new GenericContainer<>(DockerImageName.parse("redis:7-alpine")).withExposedPorts(6379);

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.r2dbc.url", () -> "r2dbc:postgresql://%s:%d/%s".formatted(postgres.getHost(), postgres.getMappedPort(5432), postgres.getDatabaseName()));
        registry.add("spring.r2dbc.username", postgres::getUsername);
        registry.add("spring.r2dbc.password", postgres::getPassword);
        registry.add("spring.flyway.url", postgres::getJdbcUrl);
        registry.add("spring.flyway.user", postgres::getUsername);
        registry.add("spring.flyway.password", postgres::getPassword);
        registry.add("spring.data.redis.host", redisContainer::getHost);
        registry.add("spring.data.redis.port", () -> redisContainer.getMappedPort(6379));
    }

    @Autowired ApplicationContext application;
    @Autowired DocumentRepository documents;
    @Autowired ChunkRepository chunks;
    @Autowired ChildChunkEmbeddingRepository embeddings;
    @Autowired DatabaseClient database;
    @Autowired ReactiveStringRedisTemplate redis;
    @Autowired ReactiveTransactionManager transactions;
    @Autowired LiveQueryIntegrationTest.FixtureState model;
    WebTestClient client;
    RequestContext identity;

    @BeforeEach void setup() {
        client = WebTestClient.bindToApplicationContext(application).configureClient().responseTimeout(Duration.ofSeconds(15)).build();
        identity = RequestContext.fromHeaders("cache-" + UUID.randomUUID(), "alice");
        model.embeddingCalls.set(0); model.answerCalls.set(0); model.beforeAnswer.set(Mono::empty);
        model.resetRewrite();
    }

    @Test void resolvedQuestionControlsCacheKeyAcrossDifferentHistoriesAndNeverCachesHistory() {
        var doc = fixture(identity);
        java.util.function.Function<QueryRequest, WebTestClient.ResponseSpec> post = request -> client.post().uri("/api/v1/query")
                .header("X-Tenant-Id", identity.tenantId()).header("X-Actor-Id", identity.actorId()).bodyValue(request).exchange();
        post.apply(new QueryRequest("first", "security policy 2024", List.of(doc.documentId()), 5, 1000, true, "documents"))
                .expectStatus().isOk().expectBody().jsonPath("$.retrievalCacheStatus").isEqualTo("miss");
        var followUp = LiveQueryIntegrationTest.historyRequest(doc.documentId(), true);
        model.resolution.set(new com.nexusagent.query.application.QuestionResolution("rewritten", "security policy 2024", null, "RESOLVED_REFERENCES"));
        post.apply(followUp).expectStatus().isOk().expectBody().jsonPath("$.retrievalCacheStatus").isEqualTo("hit")
                .jsonPath("$.queryResolution.originalQuestion").isEqualTo("What about it?");
        model.resolution.set(new com.nexusagent.query.application.QuestionResolution("rewritten", "security policy 2025", null, "RESOLVED_REFERENCES"));
        var changedHistory = new QueryRequest("other-page", followUp.question(), followUp.documentIds(), 5, 1000, true, "documents",
                List.of(new com.nexusagent.query.api.ConversationTurn("policy 2025?", "another-history-answer", false)));
        post.apply(changedHistory).expectStatus().isOk().expectBody().jsonPath("$.retrievalCacheStatus").isEqualTo("miss");
        assertThat(model.rewriteCalls).hasValue(2); assertThat(model.embeddingCalls).hasValue(2); assertThat(model.answerCalls).hasValue(3);
        var values = redis.keys("retrieval:live-context-v1:*:context").flatMap(key -> redis.opsForValue().get(key)).collectList().block();
        assertThat(values).allSatisfy(value -> assertThat(value).doesNotContain("old-private-answer", "another-history-answer", "queryResolution", "What about it?"));
    }

    @Test void repeatedQueryHitsRedisButStillGeneratesANewAnswerAndRealSseTrace() {
        var doc = fixture(identity);
        query(identity, List.of(doc.documentId()), "documents", false).expectStatus().isOk().expectBody()
                .jsonPath("$.retrievalCacheStatus").isEqualTo("miss");
        var events = query(identity, List.of(doc.documentId()), "documents", true).expectStatus().isOk()
                .expectHeader().contentTypeCompatibleWith(MediaType.TEXT_EVENT_STREAM).returnResult(QueryStreamEvent.class)
                .getResponseBody().collectList().block(Duration.ofSeconds(15));
        var response = events.get(events.size() - 1).response();
        assertThat(response.retrievalCacheStatus()).isEqualTo("hit");
        assertThat(response.contextDebug().debugMetadata().allocationStrategy()).isEqualTo("child-first-v1");
        assertThat(response.stages()).anySatisfy(s -> {
            assertThat(s.stage()).isEqualTo("child_selection"); assertThat(s.status()).isEqualTo("skipped");
            assertThat(s.summary()).containsEntry("reason", "cache_reuse");
        });
        assertThat(response.citations().get(0).childChunkId()).isEqualTo(doc.childChunks().get(0).id());
        assertThat(events).allMatch(e -> e.traceId().equals(response.traceId()));
        assertThat(response.stages()).anySatisfy(s -> {
            assertThat(s.stage()).isEqualTo("vector_search"); assertThat(s.status()).isEqualTo("skipped");
            assertThat(s.summary()).containsEntry("reason", "cache_reuse");
        });
        assertThat(model.embeddingCalls).hasValue(1); assertThat(model.answerCalls).hasValue(2);
        var keys = redis.keys("retrieval:live-context-v1:*:context").collectList().block();
        assertThat(keys).isNotEmpty();
        for (String key : keys) {
            var ttl = redis.getExpire(key).block();
            assertThat(ttl).isPositive().isLessThanOrEqualTo(Duration.ofMinutes(15));
        }
    }

    @Test void tenantAndPrivateActorDoNotShareCache() {
        var a = fixture(identity);
        var other = RequestContext.fromHeaders("cache-" + UUID.randomUUID(), "alice");
        var b = fixture(other);
        query(identity, List.of(a.documentId()), "documents", false).expectStatus().isOk();
        query(other, List.of(b.documentId()), "documents", false).expectStatus().isOk().expectBody()
                .jsonPath("$.retrievalCacheStatus").isEqualTo("miss")
                .jsonPath("$.citations[0].documentId").isEqualTo(b.documentId().toString());
        query(RequestContext.fromHeaders(identity.tenantId(), "bob"), List.of(a.documentId()), "documents", false)
                .expectStatus().isNotFound();
        query(other, List.of(a.documentId()), "documents", false).expectStatus().isNotFound();
        query(identity, List.of(a.documentId()), "documents", false).expectStatus().isOk().expectBody()
                .jsonPath("$.retrievalCacheStatus").isEqualTo("hit");
        assertThat(model.embeddingCalls).hasValue(2); assertThat(model.answerCalls).hasValue(3);
    }

    @Test void sameModelReembeddingAndForceRechunkInvalidateWithoutWaitingForTtl() {
        var doc = fixture(identity);
        query(identity, List.of(doc.documentId()), "documents", false).expectStatus().isOk();
        long before = revision(doc.documentId());
        embed(doc);
        assertThat(revision(doc.documentId())).isGreaterThan(before);
        query(identity, List.of(doc.documentId()), "documents", false).expectStatus().isOk().expectBody()
                .jsonPath("$.retrievalCacheStatus").isEqualTo("miss");
        var replacement = chunks.replaceChunks(doc.documentId(), plan(), OffsetDateTime.now()).block();
        query(identity, List.of(doc.documentId()), "documents", false).expectStatus().isEqualTo(409);
        embed(replacement);
        query(identity, List.of(doc.documentId()), "documents", false).expectStatus().isOk().expectBody()
                .jsonPath("$.retrievalCacheStatus").isEqualTo("miss")
                .jsonPath("$.citations[0].childChunkId").isEqualTo(replacement.childChunks().get(0).id().toString());
        assertThat(model.embeddingCalls).hasValue(3);
    }

    @Test void newReadyDocumentAndVisibilityChangesInvalidateLibraryScope() {
        var a = fixture(identity);
        query(identity, List.of(), "library", false).expectStatus().isOk();
        var b = fixture(identity);
        query(identity, List.of(), "library", false).expectStatus().isOk().expectBody()
                .jsonPath("$.retrievalCacheStatus").isEqualTo("miss").jsonPath("$.scope.searchedDocumentCount").isEqualTo(2);
        database.sql("UPDATE documents SET owner_id='bob' WHERE id=:id").bind("id", b.documentId()).fetch().rowsUpdated().block();
        query(identity, List.of(), "library", false).expectStatus().isOk().expectBody()
                .jsonPath("$.scope.searchedDocumentCount").isEqualTo(1).jsonPath("$.citations[0].documentId").isEqualTo(a.documentId().toString());
    }

    @Test void revokedAccessAndSameModelUpdateDuringAnswerPreventCachedOutputRelease() {
        var doc = fixture(identity);
        query(identity, List.of(doc.documentId()), "documents", false).expectStatus().isOk();
        model.beforeAnswer.set(() -> embeddings.upsert(doc.childChunks().get(0), LiveQueryIntegrationTest.vector(), MODEL, OffsetDateTime.now()).then());
        query(identity, List.of(doc.documentId()), "documents", false).expectStatus().isEqualTo(409).expectBody()
                .jsonPath("$.code").isEqualTo("DOCUMENT_CHANGED").jsonPath("$.answer").doesNotExist();
        model.beforeAnswer.set(() -> database.sql("UPDATE documents SET owner_id='bob' WHERE id=:id")
                .bind("id", doc.documentId()).fetch().rowsUpdated().then());
        query(identity, List.of(doc.documentId()), "documents", false).expectStatus().isNotFound().expectBody()
                .jsonPath("$.answer").doesNotExist();
    }

    @Test void revisionRollsBackTogetherWithDataAndPermissionChangesAdvanceIt() {
        var doc = fixture(identity);
        long before = revision(doc.documentId());
        TransactionalOperator.create(transactions).execute(status -> {
            status.setRollbackOnly();
            return database.sql("UPDATE child_chunk_embeddings SET updated_at=now() WHERE document_id=:id")
                    .bind("id", doc.documentId()).fetch().rowsUpdated().then(version(doc.documentId()));
        }).as(reactor.test.StepVerifier::create).assertNext(v -> assertThat(v).isGreaterThan(before)).verifyComplete();
        assertThat(revision(doc.documentId())).isEqualTo(before);
        database.sql("UPDATE documents SET visibility='TENANT' WHERE id=:id").bind("id", doc.documentId()).fetch().rowsUpdated().block();
        assertThat(revision(doc.documentId())).isGreaterThan(before);
    }

    @Test void paraphraseUsesSemanticCacheWithOneEmbeddingAndFreshAnswerWithoutReseeding() {
        var doc = fixture(identity);
        var ids = List.of(doc.documentId());
        query(identity, ids, "documents", false, "What does the security policy require?", true, 5, 1000)
                .expectStatus().isOk().expectBody().jsonPath("$.retrievalCacheStatus").isEqualTo("miss");
        var scopeKeys = redis.keys("retrieval:semantic-context-v1:*").collectList().block();
        assertThat(scopeKeys).isNotEmpty();
        long sourcesBefore = redis.opsForZSet().size(scopeKeys.get(scopeKeys.size() - 1)).block();
        var events = query(identity, ids, "documents", true, "Which security policy requirement applies?", true, 5, 1000)
                .expectStatus().isOk().returnResult(QueryStreamEvent.class).getResponseBody().collectList().block();
        var response = events.get(events.size() - 1).response();
        assertThat(response.retrievalCacheStatus()).isEqualTo("semantic_hit");
        assertThat(response.contextDebug().query()).isEqualTo("Which security policy requirement applies?");
        assertThat(response.stages()).anySatisfy(stage -> {
            assertThat(stage.stage()).isEqualTo("query_embedding"); assertThat(stage.status()).isEqualTo("succeeded");
        }).anySatisfy(stage -> {
            assertThat(stage.stage()).isEqualTo("semantic_cache_lookup"); assertThat(stage.summary()).containsEntry("cacheStatus", "semantic_hit");
        }).anySatisfy(stage -> {
            assertThat(stage.stage()).isEqualTo("vector_search"); assertThat(stage.status()).isEqualTo("skipped");
        });
        assertThat(events).allMatch(event -> event.traceId().equals(response.traceId()));
        assertThat(model.embeddingCalls).hasValue(2); assertThat(model.answerCalls).hasValue(2);
        assertThat(redis.opsForZSet().size(scopeKeys.get(scopeKeys.size() - 1)).block()).isEqualTo(sourcesBefore);
        query(identity, ids, "documents", false, "Which security policy requirement applies?", false, 5, 1000)
                .expectStatus().isOk().expectBody().jsonPath("$.retrievalCacheStatus").doesNotExist()
                .jsonPath("$.contextDebug").doesNotExist().jsonPath("$.stages").doesNotExist();
        assertThat(model.embeddingCalls).hasValue(3); assertThat(model.answerCalls).hasValue(3);
    }

    @Test void semanticScopeHonorsPublicActorTenantSettingsRevisionsAndLexicalGuards() {
        var doc = fixture(identity); var ids = List.of(doc.documentId());
        database.sql("UPDATE documents SET visibility='TENANT' WHERE id=:id").bind("id", doc.documentId()).fetch().rowsUpdated().block();
        query(identity, ids, "documents", false, "What policy applies in 2024?", true, 5, 1000).expectStatus().isOk();
        for (String question : List.of("What policy applies in 2025?", "What policy does not apply in 2024?", "Compare policies in 2024")) {
            query(identity, ids, "documents", false, question, true, 5, 1000).expectStatus().isOk().expectBody()
                    .jsonPath("$.retrievalCacheStatus").isEqualTo("miss");
        }
        query(RequestContext.fromHeaders(identity.tenantId(), "bob"), ids, "documents", false,
                "Which policy applies in 2024?", true, 5, 1000).expectStatus().isOk().expectBody().jsonPath("$.retrievalCacheStatus").isEqualTo("miss");
        query(RequestContext.fromHeaders("foreign", "alice"), ids, "documents", false,
                "Which policy applies in 2024?", true, 5, 1000).expectStatus().isNotFound();
        query(identity, ids, "documents", false, "Which policy applies in 2024?", true, 6, 1000).expectStatus().isOk().expectBody().jsonPath("$.retrievalCacheStatus").isEqualTo("miss");
        query(identity, ids, "documents", false, "Which policy applies in 2024?", true, 5, 1001).expectStatus().isOk().expectBody().jsonPath("$.retrievalCacheStatus").isEqualTo("miss");
        embed(doc);
        query(identity, ids, "documents", false, "Which policy applies in 2024?", true, 5, 1000).expectStatus().isOk().expectBody().jsonPath("$.retrievalCacheStatus").isEqualTo("miss");
    }

    @Test @Order(Integer.MAX_VALUE) @DirtiesContext
    void actualRedisOutageDoesNotPreventAQuery() {
        var doc = fixture(identity);
        redisContainer.stop();
        query(identity, List.of(doc.documentId()), "documents", false, "What is the policy?", true, 5, 1000).expectStatus().isOk().expectBody()
                .jsonPath("$.retrievalCacheStatus").isEqualTo("miss").jsonPath("$.answerStatus").isEqualTo("answered");
        assertThat(model.embeddingCalls).hasValue(1); assertThat(model.answerCalls).hasValue(1);
    }

    private WebTestClient.ResponseSpec query(RequestContext actor, List<UUID> ids, String scope, boolean stream) {
        return query(actor, ids, scope, stream, "security policy", true, 5, 1000);
    }
    private WebTestClient.ResponseSpec query(RequestContext actor, List<UUID> ids, String scope, boolean stream,
                                              String question, boolean debug, int topK, int budget) {
        return client.post().uri(stream ? "/api/v1/query/stream" : "/api/v1/query")
                .header("X-Tenant-Id", actor.tenantId()).header("X-Actor-Id", actor.actorId())
                .bodyValue(new QueryRequest("cache-session", question, ids, topK, budget, debug, scope)).exchange();
    }
    private ChunkedDocument fixture(RequestContext actor) {
        UUID id = UUID.randomUUID();
        var result = documents.save(DocumentMetadata.stored(id, actor.tenantId(), actor.actorId(), DocumentVisibility.PRIVATE,
                "synthetic.md", "text/markdown", TEXT.length(), "0".repeat(64), "test", "test/" + id, OffsetDateTime.now()))
                .flatMap(doc -> chunks.replaceChunks(doc.id(), plan(), OffsetDateTime.now())).block();
        embed(result); return result;
    }
    private void embed(ChunkedDocument doc) {
        embeddings.upsert(doc.childChunks().get(0), LiveQueryIntegrationTest.vector(), MODEL, OffsetDateTime.now()).block();
    }
    private ParentChildChunkPlan plan() {
        UUID parent = UUID.randomUUID();
        return new ParentChildChunkPlan(List.of(new ParentChunkDraft(parent, 0, TEXT, 0, TEXT.length(), 12)),
                List.of(new ChildChunkDraft(UUID.randomUUID(), parent, 0, TEXT, 0, TEXT.length(), 12)));
    }
    private Mono<Long> version(UUID id) {
        return database.sql("SELECT retrieval_revision FROM documents WHERE id=:id").bind("id", id)
                .map(row -> row.get("retrieval_revision", Long.class)).one();
    }
    private long revision(UUID id) { return version(id).block(); }
}
