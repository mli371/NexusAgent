package com.nexusagent.embeddings.repository;

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
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
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
class ChildChunkEmbeddingRepositoryIntegrationTest {

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

    @Test
    void persistsEmbeddingsForChildChunks() {
        UUID documentId = UUID.randomUUID();
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC).withNano(0);

        StepVerifier.create(saveDocumentWithChunks(documentId, now)
                        .flatMapMany(chunked -> Flux.fromIterable(chunked.childChunks()))
                        .concatMap(childChunk -> EMBEDDING_PROVIDER.embed(childChunk.text())
                                .flatMap(embedding -> embeddingRepository.upsert(
                                        childChunk,
                                        embedding,
                                        EMBEDDING_PROVIDER.modelInfo(),
                                        now
                                )))
                        .then(embeddingRepository.countByDocumentId(documentId)))
                .expectNext(2L)
                .verifyComplete();
    }

    @Test
    void vectorSearchReturnsMostSimilarChildChunkFirst() {
        UUID documentId = UUID.randomUUID();
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC).withNano(0);
        EmbeddingModelInfo modelInfo = EMBEDDING_PROVIDER.modelInfo();

        StepVerifier.create(saveDocumentWithChunks(documentId, now)
                        .flatMapMany(chunked -> Flux.fromIterable(chunked.childChunks()))
                        .concatMap(childChunk -> EMBEDDING_PROVIDER.embed(childChunk.text())
                                .flatMap(embedding -> embeddingRepository.upsert(childChunk, embedding, modelInfo, now)))
                        .then(EMBEDDING_PROVIDER.embed("security policy access"))
                        .flatMapMany(queryEmbedding -> vectorSearchRepository.search(queryEmbedding, 2))
                        .collectList())
                .assertNext(results -> {
                    assertThat(results).hasSize(2);
                    assertThat(results.get(0).previewText()).contains("security policy");
                    assertThat(results.get(0).distance()).isLessThanOrEqualTo(results.get(1).distance());
                })
                .verifyComplete();
    }

    private Mono<com.nexusagent.chunking.domain.ChunkedDocument> saveDocumentWithChunks(
            UUID documentId,
            OffsetDateTime timestamp
    ) {
        DocumentMetadata document = DocumentMetadata.stored(
                documentId,
                "embedding-notes.txt",
                "text/plain",
                100,
                "abc123abc123abc123abc123abc123abc123abc123abc123abc123abc123abcd",
                "bucket",
                "documents/%s/embedding-notes.txt".formatted(documentId),
                timestamp
        );

        UUID parentId = UUID.randomUUID();
        ParentChildChunkPlan plan = new ParentChildChunkPlan(
                List.of(new ParentChunkDraft(parentId, 0, "Parent context text", 0, 19, 3)),
                List.of(
                        new ChildChunkDraft(UUID.randomUUID(), parentId, 0, "security policy access controls", 0, 31, 4),
                        new ChildChunkDraft(UUID.randomUUID(), parentId, 1, "cafeteria menu lunch schedule", 32, 61, 4)
                )
        );

        return documentRepository.save(document)
                .then(chunkRepository.replaceChunks(documentId, plan, timestamp));
    }
}
