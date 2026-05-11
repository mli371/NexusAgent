package com.nexusagent.chunking.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import com.nexusagent.chunking.domain.ChildChunkDraft;
import com.nexusagent.chunking.domain.ParentChildChunkPlan;
import com.nexusagent.chunking.domain.ParentChunkDraft;
import com.nexusagent.documents.domain.DocumentMetadata;
import com.nexusagent.documents.repository.DocumentRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import reactor.test.StepVerifier;

@SpringBootTest
@Testcontainers(disabledWithoutDocker = true)
class ChunkRepositoryIntegrationTest {

    private static final DockerImageName PGVECTOR_IMAGE = DockerImageName
            .parse("pgvector/pgvector:pg16")
            .asCompatibleSubstituteFor("postgres");

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

    @Test
    void replaceChunksPersistsParentChildRelationships() {
        UUID documentId = UUID.randomUUID();
        UUID parentId = UUID.randomUUID();
        UUID childId = UUID.randomUUID();
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC).withNano(0);
        DocumentMetadata document = DocumentMetadata.stored(
                documentId,
                "notes.txt",
                "text/plain",
                50,
                "abc123abc123abc123abc123abc123abc123abc123abc123abc123abc123abcd",
                "bucket",
                "documents/%s/notes.txt".formatted(documentId),
                now
        );
        ParentChildChunkPlan plan = new ParentChildChunkPlan(
                List.of(new ParentChunkDraft(parentId, 0, "Parent context text", 0, 19, 3)),
                List.of(new ChildChunkDraft(childId, parentId, 0, "Parent context", 0, 14, 2))
        );

        StepVerifier.create(documentRepository.save(document)
                        .then(chunkRepository.replaceChunks(documentId, plan, now)))
                .assertNext(chunked -> {
                    assertThat(chunked.parentChunks()).hasSize(1);
                    assertThat(chunked.childChunks()).hasSize(1);
                    assertThat(chunked.childChunks().get(0).parentChunkId()).isEqualTo(parentId);
                    assertThat(chunked.childChunks().get(0).documentId()).isEqualTo(documentId);
                })
                .verifyComplete();
    }

    @Test
    void replaceChunksDeletesOldRowsBeforeInsertingNewRows() {
        UUID documentId = UUID.randomUUID();
        UUID firstParentId = UUID.randomUUID();
        UUID firstChildId = UUID.randomUUID();
        UUID secondParentId = UUID.randomUUID();
        UUID secondChildId = UUID.randomUUID();
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC).withNano(0);
        DocumentMetadata document = DocumentMetadata.stored(
                documentId,
                "replace-notes.txt",
                "text/plain",
                50,
                "abc123abc123abc123abc123abc123abc123abc123abc123abc123abc123abce",
                "bucket",
                "documents/%s/replace-notes.txt".formatted(documentId),
                now
        );
        ParentChildChunkPlan firstPlan = new ParentChildChunkPlan(
                List.of(new ParentChunkDraft(firstParentId, 0, "First parent text", 0, 17, 3)),
                List.of(new ChildChunkDraft(firstChildId, firstParentId, 0, "First child", 0, 11, 2))
        );
        ParentChildChunkPlan secondPlan = new ParentChildChunkPlan(
                List.of(new ParentChunkDraft(secondParentId, 0, "Second parent text", 0, 18, 3)),
                List.of(new ChildChunkDraft(secondChildId, secondParentId, 0, "Second child", 0, 12, 2))
        );

        StepVerifier.create(documentRepository.save(document)
                        .then(chunkRepository.replaceChunks(documentId, firstPlan, now))
                        .then(chunkRepository.replaceChunks(documentId, secondPlan, now))
                        .then(chunkRepository.findByDocumentId(documentId)))
                .assertNext(chunked -> {
                    assertThat(chunked.parentChunks()).hasSize(1);
                    assertThat(chunked.childChunks()).hasSize(1);
                    assertThat(chunked.parentChunks().get(0).id()).isEqualTo(secondParentId);
                    assertThat(chunked.childChunks().get(0).id()).isEqualTo(secondChildId);
                    assertThat(chunked.parentChunks().get(0).id()).isNotEqualTo(firstParentId);
                    assertThat(chunked.childChunks().get(0).id()).isNotEqualTo(firstChildId);
                })
                .verifyComplete();
    }
}
