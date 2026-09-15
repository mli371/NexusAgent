package com.nexusagent.query.live;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexusagent.chunking.domain.*;
import com.nexusagent.chunking.repository.ChunkRepository;
import com.nexusagent.common.context.RequestContext;
import com.nexusagent.documents.domain.DocumentMetadata;
import com.nexusagent.documents.domain.DocumentVisibility;
import com.nexusagent.documents.repository.DocumentRepository;
import com.nexusagent.embeddings.domain.EmbeddingModelInfo;
import com.nexusagent.embeddings.domain.EmbeddingVector;
import com.nexusagent.embeddings.repository.ChildChunkEmbeddingRepository;
import com.nexusagent.model.OpenAiHttpClient;
import com.nexusagent.query.api.QueryRequest;
import com.nexusagent.query.api.QueryStreamEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import reactor.core.publisher.Mono;

@SpringBootTest(properties = {"nexus.redis.enabled=false", "nexus.agent.enabled=false",
        "nexus.query.answer-provider=openai", "nexus.embeddings.provider=openai", "nexus.embeddings.dimension=384",
        "nexus.embeddings.model=text-embedding-3-small", "nexus.openai.api-key=test-key-not-real",
        "nexus.openai.answer-model=gpt-5.6-luna", "spring.config.import="})
@Import(LiveQueryIntegrationTest.ModelFixture.class)
@Testcontainers(disabledWithoutDocker = true)
class LiveQueryIntegrationTest {
    private static final RequestContext OWNER = RequestContext.fromHeaders("live-a", "alice");
    private static final EmbeddingModelInfo MODEL = new EmbeddingModelInfo("openai", "text-embedding-3-small", 384);
    private static final String TEXT = "Synthetic security policy requires approval before accessing confidential documents.";
    @Container
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(DockerImageName
            .parse("pgvector/pgvector:pg16").asCompatibleSubstituteFor("postgres"));

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry properties) {
        properties.add("spring.r2dbc.url", () -> "r2dbc:postgresql://%s:%d/%s".formatted(
                postgres.getHost(), postgres.getMappedPort(5432), postgres.getDatabaseName()));
        properties.add("spring.r2dbc.username", postgres::getUsername);
        properties.add("spring.r2dbc.password", postgres::getPassword);
        properties.add("spring.flyway.url", postgres::getJdbcUrl);
        properties.add("spring.flyway.user", postgres::getUsername);
        properties.add("spring.flyway.password", postgres::getPassword);
    }

    @Autowired ApplicationContext application;
    @Autowired DocumentRepository documents;
    @Autowired ChunkRepository chunks;
    @Autowired ChildChunkEmbeddingRepository embeddings;
    @Autowired DatabaseClient db;
    @Autowired ObjectMapper mapper;
    @Autowired FixtureState model;
    WebTestClient client;

    @BeforeEach
    void reset() {
        client = WebTestClient.bindToApplicationContext(application).configureClient().responseTimeout(Duration.ofSeconds(15)).build();
        model.embeddingCalls.set(0);
        model.answerCalls.set(0);
        model.beforeAnswer.set(Mono::empty);
    }

    @Test
    void fullRestPipelineUsesRealRepositoriesAndFiltersFinalCitations() {
        var document = fixture(true, MODEL);
        var result = post("/api/v1/query", document.documentId(), OWNER, true).expectStatus().isOk().expectBody()
                .jsonPath("$.answerStatus").isEqualTo("answered")
                .jsonPath("$.answerProvider").isEqualTo("openai")
                .jsonPath("$.answerModel").isEqualTo("gpt-5.6-luna")
                .jsonPath("$.retrievalCacheStatus").isEqualTo("bypassed")
                .jsonPath("$.citations[0].childChunkId").isEqualTo(document.childChunks().get(0).id().toString())
                .jsonPath("$.retrievalDebug.vectorCandidates[0].documentId").isEqualTo(document.documentId().toString())
                .jsonPath("$.retrievalDebug.fullTextCandidates[0].documentId").isEqualTo(document.documentId().toString())
                .jsonPath("$.contextDebug.expandedParentContexts.length()").isEqualTo(1)
                .returnResult();
        assertThat(model.embeddingCalls).hasValue(1);
        assertThat(model.answerCalls).hasValue(1);
        String payload = new String(result.getResponseBody(), java.nio.charset.StandardCharsets.UTF_8);
        assertThat(payload).contains("query_embedding", "vector_search", "full_text_search", "rrf_fusion", "reranking",
                "parent_expansion", "context_building", "citation_validation");
        var audit = db.sql("SELECT metadata_json::text AS metadata FROM audit_events WHERE trace_id='live-trace' ORDER BY created_at DESC LIMIT 1")
                .map(row -> row.get("metadata", String.class)).one().block(Duration.ofSeconds(5));
        assertThat(audit).contains("questionHash", "bypassed").doesNotContain(TEXT, "security policy");
    }

    @Test
    void twoIdenticalRequestsRebuildContextWithoutCacheReuseAndHideInternals() {
        var document = fixture(true, MODEL);
        for (int n = 0; n < 2; n++) {
            post("/api/v1/query", document.documentId(), OWNER, false).expectStatus().isOk().expectBody()
                    .jsonPath("$.finalContextText").doesNotExist().jsonPath("$.contextDebug").doesNotExist()
                    .jsonPath("$.retrievalDebug").doesNotExist().jsonPath("$.stages").doesNotExist()
                    .jsonPath("$.retrievalCacheStatus").doesNotExist();
        }
        assertThat(model.embeddingCalls).hasValue(2);
        assertThat(model.answerCalls).hasValue(2);
    }

    @Test
    void sseRunsExactlyOnceWithActualStageBoundariesAndOneTrace() {
        var document = fixture(true, MODEL);
        List<QueryStreamEvent> events = post("/api/v1/query/stream", document.documentId(), OWNER, true)
                .expectStatus().isOk().expectHeader().contentTypeCompatibleWith(MediaType.TEXT_EVENT_STREAM)
                .returnResult(QueryStreamEvent.class).getResponseBody().collectList().block(Duration.ofSeconds(15));
        assertThat(model.embeddingCalls).hasValue(1);
        assertThat(model.answerCalls).hasValue(1);
        assertThat(events).allMatch(event -> event.traceId().equals("live-trace"));
        assertThat(events.get(0).type()).isEqualTo("received");
        assertThat(events.subList(events.size() - 2, events.size())).extracting(QueryStreamEvent::type).containsExactly("message", "completed");
        var succeeded = events.stream().filter(event -> event.stage() != null && "succeeded".equals(event.stage().status()))
                .map(event -> event.stage().stage()).toList();
        assertThat(succeeded).contains("access_check", "embedding_readiness", "query_embedding", "vector_search", "full_text_search",
                "rrf_fusion", "reranking", "parent_expansion", "context_building", "answer_generation", "citation_validation");
        assertThat(succeeded.indexOf("rrf_fusion")).isGreaterThan(succeeded.indexOf("full_text_search"));
        assertThat(succeeded.indexOf("rrf_fusion")).isGreaterThan(succeeded.indexOf("vector_search"));
        assertThat(succeeded.indexOf("answer_generation")).isGreaterThan(succeeded.indexOf("context_building"));
    }

    @Test
    void otherTenantAndPrivateNonOwnerCannotReachModelForRestOrSse() {
        var document = fixture(true, MODEL);
        for (var actor : List.of(RequestContext.fromHeaders("live-b", "alice"), RequestContext.fromHeaders("live-a", "bob"))) {
            for (String path : List.of("/api/v1/query", "/api/v1/query/stream")) {
                post(path, document.documentId(), actor, true).expectStatus().isNotFound()
                        .expectHeader().contentTypeCompatibleWith(MediaType.APPLICATION_JSON).expectBody()
                        .jsonPath("$.code").isEqualTo("DOCUMENT_NOT_ACCESSIBLE")
                        .jsonPath("$.traceId").isEqualTo("live-trace");
            }
        }
        assertThat(model.embeddingCalls).hasValue(0);
        assertThat(model.answerCalls).hasValue(0);
    }

    @Test
    void missingAndMismatchedEmbeddingsAre409BeforeSseStarts() {
        var missing = fixture(false, MODEL);
        var mismatch = fixture(true, new EmbeddingModelInfo("local", "old-model", 384));
        post("/api/v1/query/stream", missing.documentId(), OWNER, true).expectStatus().isEqualTo(409)
                .expectBody().jsonPath("$.code").isEqualTo("EMBEDDING_INCOMPLETE");
        post("/api/v1/query", mismatch.documentId(), OWNER, true).expectStatus().isEqualTo(409)
                .expectBody().jsonPath("$.code").isEqualTo("EMBEDDING_MODEL_MISMATCH");
        assertThat(model.embeddingCalls).hasValue(0);
        assertThat(model.answerCalls).hasValue(0);
    }

    @Test
    void revocationDuringAnswerPreventsSendingAnswerOrContext() {
        var document = fixture(true, MODEL);
        model.beforeAnswer.set(() -> db.sql("UPDATE documents SET owner_id='bob' WHERE id=:id")
                .bind("id", document.documentId()).fetch().rowsUpdated().then());
        post("/api/v1/query", document.documentId(), OWNER, true).expectStatus().isNotFound().expectBody()
                .jsonPath("$.answer").doesNotExist().jsonPath("$.finalContextText").doesNotExist()
                .jsonPath("$.traceId").isEqualTo("live-trace");
        assertThat(model.answerCalls).hasValue(1);
    }

    @Test
    void rechunkDuringAnswerPreventsServingStaleCitations() {
        var document = fixture(true, MODEL);
        model.beforeAnswer.set(() -> chunks.replaceChunks(document.documentId(), plan(), OffsetDateTime.now()).then());
        post("/api/v1/query", document.documentId(), OWNER, true).expectStatus().isEqualTo(409).expectBody()
                .jsonPath("$.answer").doesNotExist().jsonPath("$.code").isEqualTo("EMBEDDING_INCOMPLETE");
    }

    @Test
    void rechunkAndReembedDuringAnswerRejectsOldChunkIdsEvenWhenCoverageIsComplete() {
        var document = fixture(true, MODEL);
        model.beforeAnswer.set(() -> chunks.replaceChunks(document.documentId(), plan(), OffsetDateTime.now())
                .flatMap(result -> embeddings.upsert(result.childChunks().get(0), vector(), MODEL, OffsetDateTime.now())).then());
        post("/api/v1/query", document.documentId(), OWNER, true).expectStatus().isEqualTo(409).expectBody()
                .jsonPath("$.answer").doesNotExist().jsonPath("$.code").isEqualTo("DOCUMENT_CHANGED");
    }

    @Test
    void invalidInputReturns400EvenForStreamAndCapabilitiesReflectConfiguredMode() {
        client.post().uri("/api/v1/query/stream").contentType(MediaType.APPLICATION_JSON)
                .bodyValue(new QueryRequest("s", " ", List.of(), 0, 0, true)).exchange().expectStatus().isBadRequest();
        client.get().uri("/api/v1/query/capabilities").exchange().expectStatus().isOk().expectBody()
                .jsonPath("$.liveQueryReady").isEqualTo(true).jsonPath("$.retrievalCacheMode").isEqualTo("bypassed")
                .jsonPath("$.answerModel").isEqualTo("gpt-5.6-luna").jsonPath("$.apiKey").doesNotExist();
    }

    @Test
    void libraryFiltersBothPathsByTenantOwnerAndWholeDocumentReadiness() {
        var identity = RequestContext.fromHeaders("library-" + UUID.randomUUID(), "owner");
        var ready = fixture(true, MODEL, identity);
        fixture(false, MODEL, identity);
        fixture(true, new EmbeddingModelInfo("local", "old", 384), identity);
        storeDocument(identity).block();
        fixture(true, MODEL, RequestContext.fromHeaders(identity.tenantId(), "other-owner"));
        fixture(true, MODEL, RequestContext.fromHeaders("foreign-tenant", "owner"));
        client.post().uri("/api/v1/query").headers(headers -> {
            headers.set("X-Tenant-Id", identity.tenantId()); headers.set("X-Actor-Id", identity.actorId());
        }).bodyValue(new QueryRequest("library-session", "security policy", List.of(), 5, 1000, true, "library"))
                .exchange().expectStatus().isOk().expectBody()
                .jsonPath("$.scope.mode").isEqualTo("library")
                .jsonPath("$.scope.accessibleDocumentCount").isEqualTo(4)
                .jsonPath("$.scope.searchedDocumentCount").isEqualTo(1)
                .jsonPath("$.scope.excludedDocumentCount").isEqualTo(3)
                .jsonPath("$.scope.exclusions.CHUNKING_REQUIRED").isEqualTo(1)
                .jsonPath("$.scope.exclusions.EMBEDDING_MODEL_MISMATCH").isEqualTo(1)
                .jsonPath("$.scope.exclusions.EMBEDDING_INCOMPLETE").isEqualTo(1)
                .jsonPath("$.retrievalDebug.vectorCandidates.length()").isEqualTo(1)
                .jsonPath("$.retrievalDebug.fullTextCandidates.length()").isEqualTo(1)
                .jsonPath("$.citations[0].documentId").isEqualTo(ready.documentId().toString());
        assertThat(model.answerCalls).hasValue(1);
    }

    @Test
    void defaultLibraryScopeDoesNotSilentlyLimitToTenDocuments() {
        var identity = RequestContext.fromHeaders("library-" + UUID.randomUUID(), "owner");
        for (int index = 0; index < 12; index++) { fixture(true, MODEL, identity); }
        client.post().uri("/api/v1/query").header("X-Tenant-Id", identity.tenantId()).header("X-Actor-Id", identity.actorId())
                .bodyValue(new QueryRequest("s", "security policy", null, 5, 1000, false))
                .exchange().expectStatus().isOk().expectBody()
                .jsonPath("$.scope.searchedDocumentCount").isEqualTo(12)
                .jsonPath("$.scope.excludedDocumentCount").isEqualTo(0)
                .jsonPath("$.contextDebug").doesNotExist();
    }

    @Test
    void emptyLibraryFailsBeforeSseWithoutModelUsage() {
        client.post().uri("/api/v1/query/stream").accept(MediaType.TEXT_EVENT_STREAM)
                .header("X-Tenant-Id", "empty-" + UUID.randomUUID()).header("X-Actor-Id", "owner")
                .bodyValue(new QueryRequest("s", "security policy", null, 5, 1000, true, "library"))
                .exchange().expectStatus().isEqualTo(409).expectHeader().contentTypeCompatibleWith(MediaType.APPLICATION_JSON)
                .expectBody().jsonPath("$.code").isEqualTo("LIBRARY_NOT_READY");
        assertThat(model.answerCalls).hasValue(0);
        assertThat(model.embeddingCalls).hasValue(0);
    }

    private WebTestClient.ResponseSpec post(String path, UUID documentId, RequestContext actor, boolean debug) {
        return client.post().uri(path).accept(path.endsWith("/stream") ? MediaType.TEXT_EVENT_STREAM : MediaType.APPLICATION_JSON)
                .header("X-Tenant-Id", actor.tenantId()).header("X-Actor-Id", actor.actorId())
                .header("X-Trace-Id", "live-trace").contentType(MediaType.APPLICATION_JSON)
                .bodyValue(new QueryRequest("live-session", "security policy", List.of(documentId), 5, 1000, debug)).exchange();
    }

    private ChunkedDocument fixture(boolean embedded, EmbeddingModelInfo modelInfo) {
        return fixture(embedded, modelInfo, OWNER);
    }

    private Mono<DocumentMetadata> storeDocument(RequestContext identity) {
        UUID id = UUID.randomUUID();
        return documents.save(DocumentMetadata.stored(id, identity.tenantId(), identity.actorId(), DocumentVisibility.PRIVATE,
                "synthetic.md", "text/markdown", TEXT.length(), "0".repeat(64), "test", "test/" + id, OffsetDateTime.now()));
    }

    private ChunkedDocument fixture(boolean embedded, EmbeddingModelInfo modelInfo, RequestContext identity) {
        return storeDocument(identity).flatMap(document -> chunks.replaceChunks(document.id(), plan(), OffsetDateTime.now()))
                .flatMap(result -> embedded ? embeddings.upsert(result.childChunks().get(0), vector(), modelInfo, OffsetDateTime.now())
                        .thenReturn(result) : Mono.just(result)).block(Duration.ofSeconds(10));
    }

    private ParentChildChunkPlan plan() {
        UUID parent = UUID.randomUUID();
        return new ParentChildChunkPlan(List.of(new ParentChunkDraft(parent, 0, TEXT, 0, TEXT.length(), 12)),
                List.of(new ChildChunkDraft(UUID.randomUUID(), parent, 0, TEXT, 0, TEXT.length(), 12)));
    }

    static EmbeddingVector vector() {
        var values = new java.util.ArrayList<>(Collections.nCopies(384, 0f));
        values.set(0, 1f);
        return new EmbeddingVector(values);
    }

    static class FixtureState {
        final AtomicInteger embeddingCalls = new AtomicInteger();
        final AtomicInteger answerCalls = new AtomicInteger();
        final AtomicReference<Supplier<Mono<Void>>> beforeAnswer = new AtomicReference<>(Mono::empty);
    }

    @TestConfiguration
    static class ModelFixture {
        @Bean FixtureState fixtureState() { return new FixtureState(); }
        @Bean @Primary OpenAiHttpClient fixtureClient(FixtureState state, ObjectMapper mapper) {
            // No network transport exists on this WebClient; production adapters still parse both fixture responses.
            return new OpenAiHttpClient(WebClient.builder().exchangeFunction(request -> Mono.defer(() -> {
                if (request.url().getPath().endsWith("embeddings")) {
                    state.embeddingCalls.incrementAndGet();
                    return Mono.fromCallable(() -> response(mapper.writeValueAsString(java.util.Map.of(
                            "model", "text-embedding-3-small", "data", List.of(java.util.Map.of("index", 0, "embedding", vector().values()))))));
                }
                if (!request.url().getPath().endsWith("responses")) { return Mono.error(new AssertionError("Unexpected model route")); }
                state.answerCalls.incrementAndGet();
                return state.beforeAnswer.get().get().then(Mono.fromCallable(() -> response(mapper.writeValueAsString(java.util.Map.of(
                        "status", "completed", "output", List.of(java.util.Map.of("type", "message", "role", "assistant", "content",
                                List.of(java.util.Map.of("type", "output_text", "text", mapper.writeValueAsString(java.util.Map.of(
                                        "status", "answered", "answer", "Approval is required [C1]", "usedCitationMarkers", List.of("[C1]"))))))))))));
            })).build());
        }
        private static ClientResponse response(String body) {
            return ClientResponse.create(HttpStatus.OK).header("Content-Type", "application/json").body(body).build();
        }
    }
}
