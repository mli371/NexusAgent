package com.nexusagent.retrieval.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import com.nexusagent.chunking.domain.ChildChunkDraft;
import com.nexusagent.chunking.domain.ParentChildChunkPlan;
import com.nexusagent.chunking.domain.ParentChunkDraft;
import com.nexusagent.chunking.repository.ChunkRepository;
import com.nexusagent.documents.domain.DocumentMetadata;
import com.nexusagent.documents.repository.DocumentRepository;
import com.nexusagent.embeddings.application.LocalDeterministicEmbeddingProvider;
import com.nexusagent.embeddings.domain.EmbeddingModelInfo;
import com.nexusagent.embeddings.repository.ChildChunkEmbeddingRepository;
import com.nexusagent.embeddings.repository.VectorSearchRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
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

@SpringBootTest
@Testcontainers(disabledWithoutDocker = true)
class RetrievalRepositoryIntegrationTest {

    private static final DockerImageName PGVECTOR_IMAGE = DockerImageName
            .parse("pgvector/pgvector:pg16")
            .asCompatibleSubstituteFor("postgres");

    private static final LocalDeterministicEmbeddingProvider EMBEDDING_PROVIDER =
            new LocalDeterministicEmbeddingProvider(384);

    @Container
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(PGVECTOR_IMAGE)
            .withDatabaseName("nexusagent_test")
            .withUsername("nexus")
            .withPassword("nexus");

    @DynamicPropertySource
    static void registerProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.r2dbc.url", () -> "r2dbc:postgresql://%s:%d/%s".formatted(
                postgres.getHost(),
                postgres.getMappedPort(PostgreSQLContainer.POSTGRESQL_PORT),
                postgres.getDatabaseName()
        ));
        registry.add("spring.r2dbc.username", postgres::getUsername);
        registry.add("spring.r2dbc.password", postgres::getPassword);
        registry.add("spring.flyway.url", postgres::getJdbcUrl);
        registry.add("spring.flyway.user", postgres::getUsername);
        registry.add("spring.flyway.password", postgres::getPassword);
    }

    @Autowired
    DocumentRepository documentRepository;

    @Autowired
    ChunkRepository chunkRepository;

    @Autowired
    ChildChunkEmbeddingRepository embeddingRepository;

    @Autowired
    VectorSearchRepository vectorSearchRepository;

    @Autowired
    FullTextSearchRepository fullTextSearchRepository;

    @Test
    void flywayAppliedV5MigrationAndCreatedFullTextIndex() {
        StepVerifier.create(Mono.zip(flywayVersionApplied("5"), fullTextIndexExists()))
                .assertNext(result -> {
                    assertThat(result.getT1()).isTrue();
                    assertThat(result.getT2()).isTrue();
                })
                .verifyComplete();
    }

    @Test
    void fullTextSearchReturnsCandidatesByDescendingRankScore() {
        UUID documentId = UUID.randomUUID();

        StepVerifier.create(saveDocumentWithChunks(
                                documentId,
                                "security policy security policy access controls",
                                "security policy lunch schedule"
                        )
                        .thenMany(fullTextSearchRepository.search("security policy", List.of(documentId), 5))
                        .collectList())
                .assertNext(results -> {
                    assertThat(results).hasSize(2);
                    assertThat(results.get(0).documentId()).isEqualTo(documentId);
                    assertThat(results.get(0).previewText()).contains("security policy");
                    assertThat(results.get(0).score()).isPositive();
                    assertThat(results.get(0).score()).isGreaterThanOrEqualTo(results.get(1).score());
                })
                .verifyComplete();
    }

    @Test
    void vectorSearchReturnsCandidatesByAscendingCosineDistance() {
        UUID documentId = UUID.randomUUID();
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC).withNano(0);
        EmbeddingModelInfo modelInfo = EMBEDDING_PROVIDER.modelInfo();

        StepVerifier.create(saveDocumentWithChunks(
                                documentId,
                                "security policy access controls",
                                "cafeteria menu lunch schedule"
                        )
                        .flatMapMany(chunked -> Flux.fromIterable(chunked.childChunks()))
                        .concatMap(childChunk -> EMBEDDING_PROVIDER.embed(childChunk.text())
                                .flatMap(embedding -> embeddingRepository.upsert(childChunk, embedding, modelInfo, now)))
                        .then(EMBEDDING_PROVIDER.embed("security policy access"))
                        .flatMapMany(queryEmbedding -> vectorSearchRepository.search(queryEmbedding, List.of(documentId), 2))
                        .collectList())
                .assertNext(results -> {
                    assertThat(results).hasSize(2);
                    assertThat(results.get(0).documentId()).isEqualTo(documentId);
                    assertThat(results.get(0).previewText()).contains("security policy");
                    assertThat(results.get(0).distance()).isLessThanOrEqualTo(results.get(1).distance());
                })
                .verifyComplete();
    }

    @Test
    void documentIdFilterAppliesToVectorAndFullTextRetrievalPaths() {
        UUID includedDocumentId = UUID.randomUUID();
        UUID excludedDocumentId = UUID.randomUUID();
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC).withNano(0);

        StepVerifier.create(saveAndEmbedDocument(
                                includedDocumentId,
                                "security policy access controls for included document",
                                "included cafeteria menu",
                                now
                        )
                        .then(saveAndEmbedDocument(
                                excludedDocumentId,
                                "security policy access controls for excluded document",
                                "excluded cafeteria menu",
                                now
                        ))
                        .then(EMBEDDING_PROVIDER.embed("security policy access"))
                        .flatMapMany(queryEmbedding -> vectorSearchRepository.search(
                                queryEmbedding,
                                List.of(includedDocumentId),
                                5
                        ))
                        .collectList()
                        .zipWith(fullTextSearchRepository.search(
                                        "security policy",
                                        List.of(includedDocumentId),
                                        5
                                )
                                .collectList()))
                .assertNext(results -> {
                    assertThat(results.getT1()).isNotEmpty();
                    assertThat(results.getT1()).allSatisfy(result ->
                            assertThat(result.documentId()).isEqualTo(includedDocumentId));
                    assertThat(results.getT2()).isNotEmpty();
                    assertThat(results.getT2()).allSatisfy(result ->
                            assertThat(result.documentId()).isEqualTo(includedDocumentId));
                })
                .verifyComplete();
    }

    @Test
    void vectorSearchReturnsEmptyListWhenDocumentHasNoEmbeddings() {
        UUID documentId = UUID.randomUUID();

        StepVerifier.create(saveDocumentWithChunks(
                                documentId,
                                "security policy access controls",
                                "cafeteria menu lunch schedule"
                        )
                        .then(EMBEDDING_PROVIDER.embed("security policy access"))
                        .flatMapMany(queryEmbedding -> vectorSearchRepository.search(queryEmbedding, List.of(documentId), 5))
                        .collectList())
                .assertNext(results -> assertThat(results).isEmpty())
                .verifyComplete();
    }

    @Autowired
    DatabaseClient databaseClient;

    private Mono<Boolean> flywayVersionApplied(String version) {
        return databaseClient.sql("""
                        SELECT COUNT(*) AS row_count
                        FROM flyway_schema_history
                        WHERE version = :version
                          AND success = true
                        """)
                .bind("version", version)
                .map((row, rowMetadata) -> requireLong(row.get("row_count")) > 0)
                .one();
    }

    private Mono<Boolean> fullTextIndexExists() {
        return databaseClient.sql("""
                        SELECT COUNT(*) AS row_count
                        FROM pg_indexes
                        WHERE schemaname = 'public'
                          AND tablename = 'child_chunks'
                          AND indexname = 'idx_child_chunks_text_fts'
                          AND indexdef ILIKE '%USING gin%'
                        """)
                .map((row, rowMetadata) -> requireLong(row.get("row_count")) > 0)
                .one();
    }

    private Mono<Void> saveAndEmbedDocument(
            UUID documentId,
            String firstChildText,
            String secondChildText,
            OffsetDateTime timestamp
    ) {
        EmbeddingModelInfo modelInfo = EMBEDDING_PROVIDER.modelInfo();
        return saveDocumentWithChunks(documentId, firstChildText, secondChildText)
                .flatMapMany(chunked -> Flux.fromIterable(chunked.childChunks()))
                .concatMap(childChunk -> EMBEDDING_PROVIDER.embed(childChunk.text())
                        .flatMap(embedding -> embeddingRepository.upsert(childChunk, embedding, modelInfo, timestamp)))
                .then();
    }

    private Mono<com.nexusagent.chunking.domain.ChunkedDocument> saveDocumentWithChunks(
            UUID documentId,
            String firstChildText,
            String secondChildText
    ) {
        OffsetDateTime timestamp = OffsetDateTime.now(ZoneOffset.UTC).withNano(0);
        DocumentMetadata document = DocumentMetadata.stored(
                documentId,
                "retrieval-notes.txt",
                "text/plain",
                100,
                "abc123abc123abc123abc123abc123abc123abc123abc123abc123abc123abcd",
                "bucket",
                "documents/%s/retrieval-notes.txt".formatted(documentId),
                timestamp
        );

        UUID parentId = UUID.randomUUID();
        ParentChildChunkPlan plan = new ParentChildChunkPlan(
                List.of(new ParentChunkDraft(parentId, 0, "Parent context text", 0, 19, 3)),
                List.of(
                        new ChildChunkDraft(UUID.randomUUID(), parentId, 0, firstChildText, 0, firstChildText.length(), 4),
                        new ChildChunkDraft(
                                UUID.randomUUID(),
                                parentId,
                                1,
                                secondChildText,
                                firstChildText.length() + 1,
                                firstChildText.length() + 1 + secondChildText.length(),
                                4
                        )
                )
        );

        return documentRepository.save(document)
                .then(chunkRepository.replaceChunks(documentId, plan, timestamp));
    }

    private long requireLong(Object value) {
        if (value instanceof Long longValue) {
            return longValue;
        }
        if (value instanceof Number number) {
            return number.longValue();
        }
        throw new IllegalStateException("Missing required numeric value");
    }
}
