package com.nexusagent.documents.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

import com.nexusagent.documents.domain.DocumentMetadata;
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
class DocumentRepositoryIntegrationTest {

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

    @Test
    void saveFindAndListDocumentMetadata() {
        UUID id = UUID.randomUUID();
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC).withNano(0);
        DocumentMetadata metadata = DocumentMetadata.stored(
                id,
                "policy.txt",
                "text/plain",
                42,
                "abc123abc123abc123abc123abc123abc123abc123abc123abc123abc123abcd",
                "nexus-documents",
                "documents/%s/policy.txt".formatted(id),
                now
        );

        StepVerifier.create(documentRepository.save(metadata).then(documentRepository.findById(id)))
                .assertNext(found -> {
                    assertThat(found.id()).isEqualTo(id);
                    assertThat(found.originalFilename()).isEqualTo("policy.txt");
                    assertThat(found.contentType()).isEqualTo("text/plain");
                    assertThat(found.sizeBytes()).isEqualTo(42);
                    assertThat(found.minioBucket()).isEqualTo("nexus-documents");
                    assertThat(found.minioObjectKey()).isEqualTo("documents/%s/policy.txt".formatted(id));
                    assertThat(found.sha256()).isEqualTo(metadata.sha256());
                })
                .verifyComplete();

        StepVerifier.create(documentRepository.findAll(10, 0).collectList())
                .assertNext(documents -> assertThat(documents)
                        .extracting(DocumentMetadata::id)
                        .contains(id))
                .verifyComplete();
    }
}
