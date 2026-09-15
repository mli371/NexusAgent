package com.nexusagent.embeddings.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import com.nexusagent.chunking.domain.ChildChunkDraft;
import com.nexusagent.chunking.domain.ChunkedDocument;
import com.nexusagent.chunking.domain.ParentChildChunkPlan;
import com.nexusagent.chunking.domain.ParentChunkDraft;
import com.nexusagent.chunking.repository.ChunkRepository;
import com.nexusagent.common.context.RequestContext;
import com.nexusagent.common.error.NotFoundException;
import com.nexusagent.common.error.OperationException;
import com.nexusagent.documents.domain.DocumentMetadata;
import com.nexusagent.documents.domain.DocumentVisibility;
import com.nexusagent.documents.repository.DocumentRepository;
import com.nexusagent.embeddings.application.ChildChunkEmbeddingService;
import com.nexusagent.embeddings.application.EmbeddingProvider;
import com.nexusagent.embeddings.application.EmbeddingWriteService;
import com.nexusagent.embeddings.domain.EmbeddingModelInfo;
import com.nexusagent.embeddings.domain.EmbeddingVector;
import com.nexusagent.enterprise.ingestion.IngestionJobRepository;
import com.nexusagent.enterprise.ingestion.IngestionJobStatus;
import com.nexusagent.enterprise.ingestion.IngestionJobType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.HttpStatus;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

@SpringBootTest(properties = {"nexus.redis.enabled=false", "nexus.embeddings.provider=local", "nexus.agent.enabled=false"})
@Testcontainers(disabledWithoutDocker = true)
class EmbeddingRebuildIntegrationTest {
    private static final RequestContext OWNER = RequestContext.fromHeaders("tenant-a", "owner-a");
    private static final EmbeddingModelInfo OLD = new EmbeddingModelInfo("local", "local-deterministic-hash-384", 384);
    private static final EmbeddingModelInfo CURRENT = new EmbeddingModelInfo("openai", "text-embedding-3-small", 384);
    private static final EmbeddingVector VECTOR = new EmbeddingVector(vector());
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

    @Autowired DocumentRepository documents;
    @Autowired ChunkRepository chunks;
    @Autowired ChildChunkEmbeddingRepository embeddings;
    @Autowired ChildChunkEmbeddingService service;
    @Autowired EmbeddingWriteService writer;
    @Autowired VectorSearchRepository search;
    @Autowired IngestionJobRepository jobs;
    @Autowired DatabaseClient db;
    @MockBean EmbeddingProvider provider;

    @BeforeEach
    void provider() {
        when(provider.modelInfo()).thenReturn(CURRENT);
        when(provider.embed(anyString())).thenReturn(Mono.just(VECTOR));
    }

    @Test
    void rebuildReplacesModelPreservesChunksAndTracksSucceededJob() {
        ChunkedDocument before = fixture();
        StepVerifier.create(service.getStatus(before.documentId(), OWNER))
                .assertNext(status -> {
                    assertThat(status.complete()).isFalse();
                    assertThat(status.mismatchedChildChunkCount()).isEqualTo(2);
                    assertThat(status.matchingChildChunkCount()).isZero();
                }).verifyComplete();
        StepVerifier.create(service.embedDocument(before.documentId(), OWNER, true))
                .assertNext(status -> {
                    assertThat(status.complete()).isTrue();
                    assertThat(status.matchingChildChunkCount()).isEqualTo(2);
                    assertThat(status.mismatchedChildChunkCount()).isZero();
                }).verifyComplete();
        assertThat(chunks.findByDocumentId(before.documentId()).block(Duration.ofSeconds(5))).isEqualTo(before);
        StepVerifier.create(jobs.findByDocumentIdAndTenant(before.documentId(), OWNER.tenantId()))
                .assertNext(job -> {
                    assertThat(job.jobType()).isEqualTo(IngestionJobType.REEMBED);
                    assertThat(job.status()).isEqualTo(IngestionJobStatus.SUCCEEDED);
                }).verifyComplete();
        verify(provider).embed("first synthetic child");
        verify(provider).embed("second synthetic child");
        verify(provider, never()).embed("Synthetic parent context");
    }

    @Test
    void ordinaryEmbeddingRejectsAnotherModelBeforeCallingProvider() {
        ChunkedDocument before = fixture();
        clearInvocations(provider);
        StepVerifier.create(service.embedDocument(before.documentId(), OWNER))
                .expectErrorSatisfies(error -> assertThat(((OperationException) error).code()).isEqualTo("EMBEDDING_MODEL_MISMATCH"))
                .verify();
        verify(provider, never()).embed(anyString());
        assertOldVectors(before);
    }

    @Test
    void generationFailureDoesNotCommitFirstGeneratedVector() {
        ChunkedDocument before = fixture();
        when(provider.embed("second synthetic child")).thenReturn(Mono.error(new OperationException(
                HttpStatus.SERVICE_UNAVAILABLE, "MODEL_UNAVAILABLE", "Model unavailable")));
        StepVerifier.create(service.embedDocument(before.documentId(), OWNER, true)).expectError(OperationException.class).verify();
        assertOldVectors(before);
        StepVerifier.create(jobs.findByDocumentIdAndTenant(before.documentId(), OWNER.tenantId()))
                .assertNext(job -> assertThat(job.status()).isEqualTo(IngestionJobStatus.FAILED)).verifyComplete();
    }

    @Test
    void databaseFailureRollsBackAllUpserts() {
        ChunkedDocument before = fixture();
        List<EmbeddingWriteService.PendingEmbedding> generated = List.of(
                new EmbeddingWriteService.PendingEmbedding(before.childChunks().get(0), VECTOR),
                new EmbeddingWriteService.PendingEmbedding(before.childChunks().get(1), new EmbeddingVector(List.of(1f))));
        StepVerifier.create(writer.commit(before, generated, CURRENT, OWNER, true, now()))
                .expectError().verify();
        assertOldVectors(before);
    }

    @Test
    void chunkChangeWhileGeneratingRejectsCommit() {
        ChunkedDocument before = fixture();
        AtomicInteger calls = new AtomicInteger();
        when(provider.embed(anyString())).thenAnswer(invocation -> Mono.defer(() -> calls.incrementAndGet() == 1
                ? chunks.replaceChunks(before.documentId(), plan(), now()).thenReturn(VECTOR) : Mono.just(VECTOR)));
        StepVerifier.create(service.embedDocument(before.documentId(), OWNER, true))
                .expectErrorSatisfies(error -> assertThat(((OperationException) error).code()).isEqualTo("DOCUMENT_CHANGED"))
                .verify();
        StepVerifier.create(embeddings.countByDocumentId(before.documentId())).expectNext(0L).verifyComplete();
    }

    @Test
    void privateAndCrossTenantAccessCannotReadStatusRebuildOrSearch() {
        ChunkedDocument before = fixture();
        clearInvocations(provider);
        for (RequestContext other : List.of(RequestContext.fromHeaders("tenant-b", "owner-a"),
                RequestContext.fromHeaders("tenant-a", "other-actor"))) {
            StepVerifier.create(service.embedDocument(before.documentId(), other, true)).expectError(NotFoundException.class).verify();
            StepVerifier.create(service.getStatus(before.documentId(), other)).expectError(NotFoundException.class).verify();
            StepVerifier.create(search.search(VECTOR, List.of(before.documentId()), 10, other)).verifyComplete();
        }
        verify(provider, never()).embed(anyString());
        StepVerifier.create(jobs.findByDocumentIdAndTenant(before.documentId(), OWNER.tenantId())).verifyComplete();
    }

    @Test
    void semanticSearchFiltersModelBeforeRankingAndLimit() {
        ChunkedDocument before = fixture();
        StepVerifier.create(search.search(VECTOR, List.of(before.documentId()), 1, OWNER)).verifyComplete();
        embeddings.upsert(before.childChunks().get(1), VECTOR, CURRENT, now()).block(Duration.ofSeconds(5));
        StepVerifier.create(search.search(VECTOR, List.of(before.documentId()), 1, OWNER))
                .assertNext(result -> assertThat(result.childChunkId()).isEqualTo(before.childChunks().get(1).id()))
                .verifyComplete();
    }

    @Test
    void lateModelChangeCannotBeOverwrittenByOrdinaryCommit() {
        ChunkedDocument before = fixture();
        StepVerifier.create(writer.commit(before, List.of(new EmbeddingWriteService.PendingEmbedding(before.childChunks().get(0), VECTOR)),
                        CURRENT, OWNER, false, now()))
                .expectErrorSatisfies(error -> assertThat(((OperationException) error).code()).isEqualTo("EMBEDDING_MODEL_MISMATCH"))
                .verify();
        assertOldVectors(before);
    }

    @Test
    void accessRevokedDuringGenerationStopsNextRequestAndPreservesOldVectors() {
        ChunkedDocument before = fixture();
        AtomicInteger calls = new AtomicInteger();
        when(provider.embed(anyString())).thenAnswer(invocation -> Mono.defer(() -> {
            calls.incrementAndGet();
            return db.sql("UPDATE documents SET owner_id='new-owner' WHERE id=:id")
                    .bind("id", before.documentId()).fetch().rowsUpdated().thenReturn(VECTOR);
        }));
        StepVerifier.create(service.embedDocument(before.documentId(), OWNER, true)).expectError(NotFoundException.class).verify();
        assertThat(calls).hasValue(1);
        assertOldVectors(before);
    }

    @Test
    void chunkReplacementWaitsForEmbeddingCommitDocumentLock() {
        ChunkedDocument before = fixture();
        reactor.core.publisher.Sinks.One<Void> locked = reactor.core.publisher.Sinks.one();
        reactor.core.publisher.Sinks.One<Void> release = reactor.core.publisher.Sinks.one();
        var tx = org.springframework.transaction.reactive.TransactionalOperator.create(
                new org.springframework.r2dbc.connection.R2dbcTransactionManager(db.getConnectionFactory()));
        Mono<Void> holder = documents.lockAccessible(before.documentId(), OWNER)
                .doOnNext(id -> locked.tryEmitEmpty()).then(release.asMono()).as(tx::transactional);
        Mono<Void> contender = locked.asMono().then(Mono.defer(() -> chunks.replaceChunks(before.documentId(), plan(), now())))
                .timeout(Duration.ofMillis(150))
                .doFinally(signal -> release.tryEmitEmpty())
                .then();
        StepVerifier.create(Mono.whenDelayError(holder, contender))
                .expectError(java.util.concurrent.TimeoutException.class).verify(Duration.ofSeconds(5));
        assertThat(chunks.findByDocumentId(before.documentId()).block(Duration.ofSeconds(5))).isEqualTo(before);
        assertOldVectors(before);
    }

    private void assertOldVectors(ChunkedDocument before) {
        StepVerifier.create(embeddings.coverage(before.documentId(), OLD))
                .assertNext(coverage -> assertThat(coverage.matchingCount()).isEqualTo(2)).verifyComplete();
    }

    private ChunkedDocument fixture() {
        UUID id = UUID.randomUUID();
        DocumentMetadata document = DocumentMetadata.stored(id, OWNER.tenantId(), OWNER.actorId(), DocumentVisibility.PRIVATE,
                "synthetic.md", "text/markdown", 50, "0".repeat(64), "bucket", "synthetic/" + id, now());
        return documents.save(document).then(chunks.replaceChunks(id, plan(), now()))
                .flatMap(snapshot -> Flux.fromIterable(snapshot.childChunks())
                        .concatMap(child -> embeddings.upsert(child, VECTOR, OLD, now())).then(Mono.just(snapshot)))
                .block(Duration.ofSeconds(10));
    }

    private ParentChildChunkPlan plan() {
        UUID parent = UUID.randomUUID();
        return new ParentChildChunkPlan(List.of(new ParentChunkDraft(parent, 0, "Synthetic parent context", 0, 50, 8)),
                List.of(new ChildChunkDraft(UUID.randomUUID(), parent, 0, "first synthetic child", 0, 21, 3),
                        new ChildChunkDraft(UUID.randomUUID(), parent, 1, "second synthetic child", 22, 44, 3)));
    }

    private static OffsetDateTime now() { return OffsetDateTime.now(ZoneOffset.UTC).withNano(0); }
    private static List<Float> vector() {
        var values = new java.util.ArrayList<>(java.util.Collections.nCopies(384, 0f));
        values.set(0, 1f);
        return values;
    }
}
