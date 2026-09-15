package com.nexusagent.agentrun;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doCallRealMethod;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.nexusagent.agentrun.application.AgentRunService;
import com.nexusagent.agentrun.application.AgentRunProperties;
import com.nexusagent.agentrun.repository.AgentRunRepository;
import com.nexusagent.agentrun.tools.DocumentDiagnosticsService;
import com.nexusagent.chunking.application.ParentChildChunker;
import com.nexusagent.chunking.repository.ChunkRepository;
import com.nexusagent.documents.domain.DocumentMetadata;
import com.nexusagent.documents.domain.DocumentVisibility;
import com.nexusagent.documents.repository.DocumentRepository;
import com.nexusagent.embeddings.application.ChildChunkEmbeddingService;
import com.nexusagent.common.context.RequestContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;
import reactor.test.StepVerifier;
import reactor.core.scheduler.Schedulers;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "nexus.agent.enabled=true", "nexus.agent.worker-token=integration-test-worker-token-32-characters",
        "nexus.agent.worker-mode=scripted", "nexus.redis.enabled=false"
})
@Import(AgentRunIntegrationTest.TestClockConfig.class)
@Testcontainers(disabledWithoutDocker = true)
class AgentRunIntegrationTest {
    private static final String TOKEN = "integration-test-worker-token-32-characters";
    @Container
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(
            DockerImageName.parse("pgvector/pgvector:pg16").asCompatibleSubstituteFor("postgres"));

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.r2dbc.url", () -> "r2dbc:postgresql://%s:%d/%s".formatted(
                postgres.getHost(), postgres.getMappedPort(5432), postgres.getDatabaseName()));
        registry.add("spring.r2dbc.username", postgres::getUsername);
        registry.add("spring.r2dbc.password", postgres::getPassword);
        registry.add("spring.flyway.url", postgres::getJdbcUrl);
        registry.add("spring.flyway.user", postgres::getUsername);
        registry.add("spring.flyway.password", postgres::getPassword);
    }

    @Autowired DatabaseClient db;
    @Autowired DocumentRepository documents;
    @Autowired ChunkRepository chunks;
    @Autowired ParentChildChunker chunker;
    @Autowired ChildChunkEmbeddingService embeddings;
    @Autowired AgentRunService runs;
    @Autowired AgentRunRepository runRepository;
    @Autowired AgentRunProperties properties;
    @SpyBean DocumentDiagnosticsService diagnostics;
    @Autowired ObjectMapper mapper;
    @Autowired MutableClock clock;
    @LocalServerPort int port;
    WebTestClient client;

    @BeforeEach
    void reset() {
        clock.instant = Instant.now();
        properties.setEnabled(true);
        properties.setMaxTools(30);
        properties.setMaxRecoveries(3);
        properties.setEventPollInterval(Duration.ofMillis(100));
        properties.setToolTimeout(Duration.ofSeconds(10));
        db.sql("TRUNCATE agent_runs CASCADE").fetch().rowsUpdated().block();
        client = WebTestClient.bindToServer().baseUrl("http://localhost:" + port)
                .responseTimeout(Duration.ofSeconds(15)).build();
    }

    @Test
    void validatesInputHeadersAndCreationIdempotency() {
        UUID document = document("tenant-a", "alice", DocumentVisibility.TENANT, "policy.txt");
        ObjectNode body = request(document);
        client.post().uri("/api/v1/agent/runs").bodyValue(body).exchange().expectStatus().isBadRequest();
        client.post().uri("/api/v1/agent/runs").headers(h -> { h.set("X-Tenant-Id", "tenant-a"); h.set("X-Actor-Id", "alice"); })
                .bodyValue(mapper.createObjectNode().put("question", " ").set("documentIds", mapper.valueToTree(List.of(document))))
                .exchange().expectStatus().isBadRequest();
        JsonNode created = create(document, "same-key");
        assertThat(create(document, "same-key").path("runId")).isEqualTo(created.path("runId"));
        post("/api/v1/agent/runs").header("Idempotency-Key", "same-key")
                .bodyValue(body.put("question", "Different question")).exchange().expectStatus().isEqualTo(409);
        assertThat(created.path("executionMode").asText()).isEqualTo("scripted");
        assertThat(created.toString()).doesNotContain("claimToken", "claimHash", TOKEN);
    }

    @Test
    void enforcesTenantActorPrivateVisibilityAndRechecksRevocation() {
        UUID document = document("tenant-a", "alice", DocumentVisibility.PRIVATE, "private.txt");
        client.post().uri("/api/v1/agent/runs").header("X-Tenant-Id", "tenant-b").header("X-Actor-Id", "alice")
                .bodyValue(request(document)).exchange().expectStatus().isNotFound();
        client.post().uri("/api/v1/agent/runs").header("X-Tenant-Id", "tenant-a").header("X-Actor-Id", "bob")
                .bodyValue(request(document)).exchange().expectStatus().isNotFound();
        String id = create(document, null).path("runId").asText();
        client.get().uri("/api/v1/agent/runs/" + id).header("X-Tenant-Id", "tenant-b").header("X-Actor-Id", "alice")
                .exchange().expectStatus().isNotFound();
        client.get().uri("/api/v1/agent/runs/" + id).header("X-Tenant-Id", "tenant-a").header("X-Actor-Id", "bob")
                .exchange().expectStatus().isNotFound();
        JsonNode claim = claim();
        db.sql("UPDATE documents SET owner_id='bob' WHERE id=:id").bind("id", document).fetch().rowsUpdated().block();
        get(id).exchange().expectStatus().isNotFound();
        client.get().uri("/api/v1/agent/runs/" + id + "/events").header("X-Tenant-Id", "tenant-a")
                .header("X-Actor-Id", "alice").exchange().expectStatus().isNotFound();
        tool(claim, "inspect_document", document, UUID.randomUUID()).exchange().expectStatus().isNotFound();
    }

    @Test
    void claimsOnlyOneRunEvenWithConcurrentWorkers() {
        UUID document = document("tenant-a", "alice", DocumentVisibility.TENANT, "policy.txt");
        create(document, "one");
        create(document, "two");
        List<JsonNode> claims = Flux.merge(runs.claim(TOKEN), runs.claim(TOKEN)).collectList().block();
        assertThat(claims).hasSize(1);
        client.post().uri("/internal/agent-worker/claims").header("X-Worker-Token", TOKEN)
                .exchange().expectStatus().isNoContent();
        client.post().uri("/internal/agent-worker/claims").header("X-Worker-Token", "wrong")
                .exchange().expectStatus().isUnauthorized();
    }

    @Test
    void persistsToolResultsReplaysResponsesAndValidatesReports() {
        UUID document = document("tenant-a", "alice", DocumentVisibility.TENANT, "policy.txt");
        insertFailedJob(document);
        String id = create(document, null).path("runId").asText();
        JsonNode claim = claim();
        UUID invocation = UUID.randomUUID();
        JsonNode inspect = tool(claim, "inspect_document", document, invocation).exchange().expectStatus().isOk()
                .expectBody(JsonNode.class).returnResult().getResponseBody();
        JsonNode replay = tool(claim, "inspect_document", document, invocation).exchange().expectStatus().isOk()
                .expectBody(JsonNode.class).returnResult().getResponseBody();
        assertThat(replay).isEqualTo(inspect);
        assertThat(inspect.path("data").path("condition").asText()).isEqualTo("CHUNKING_REQUIRED");
        tool(claim, "list_ingestion_jobs", document, invocation).exchange().expectStatus().isEqualTo(409);
        tool(claim, "execute_approved_retry", document, UUID.randomUUID()).exchange().expectStatus().isBadRequest();
        JsonNode jobs = tool(claim, "list_ingestion_jobs", document, UUID.randomUUID()).exchange().expectStatus().isOk()
                .expectBody(JsonNode.class).returnResult().getResponseBody();
        assertThat(jobs.toString()).contains("UNKNOWN").doesNotContain("raw-secret", "error_message", "minio", "chunkText");
        client.get().uri("/internal/agent-worker/runs/" + id + "/tools/" + invocation)
                .header("X-Claim-Token", claim.path("claimToken").asText()).exchange().expectStatus().isOk()
                .expectBody(JsonNode.class).isEqualTo(inspect);
        ObjectNode report = report(document, inspect, jobs);
        JsonNode forged = report.deepCopy();
        ((ObjectNode) forged.path("findings").get(0)).set("observationIds", mapper.valueToTree(List.of(UUID.randomUUID(), UUID.randomUUID())));
        complete(claim, forged).exchange().expectStatus().isBadRequest();
        complete(claim, report).exchange().expectStatus().isNoContent();
        complete(claim, report).exchange().expectStatus().isNoContent();
        complete(claim, report.deepCopy().put("summary", "changed")).exchange().expectStatus().isEqualTo(409);
        get(id).exchange().expectStatus().isOk().expectBody().jsonPath("$.status").isEqualTo("SUCCEEDED");
        client.get().uri("/api/v1/agent/runs/" + id + "/events?afterSequence=2").header("X-Tenant-Id", "tenant-a")
                .header("X-Actor-Id", "alice").exchange().expectStatus().isOk().expectBody()
                .jsonPath("$.events[0].sequence").isEqualTo(3).jsonPath("$.nextSequence").isEqualTo(7);
        assertThat(db.sql("SELECT tool_count FROM agent_runs WHERE id=:id").bind("id", UUID.fromString(id))
                .map(row -> row.get("tool_count", Integer.class)).one().block()).isEqualTo(2);
    }

    @Test
    void expiresWorkerLeaseAndRejectsLateCompletion() {
        UUID document = document("tenant-a", "alice", DocumentVisibility.TENANT, "policy.txt");
        String id = create(document, null).path("runId").asText();
        JsonNode claim = claim();
        clock.instant = clock.instant.plusSeconds(46);
        get(id).exchange().expectStatus().isOk().expectBody().jsonPath("$.status").isEqualTo("QUEUED")
                .jsonPath("$.recoveryCount").isEqualTo(1);
        client.post().uri("/internal/agent-worker/runs/" + id + "/heartbeat")
                .header("X-Claim-Token", claim.path("claimToken").asText()).exchange().expectStatus().isUnauthorized();
        complete(claim, mapper.createObjectNode()).exchange().expectStatus().isUnauthorized();
        client.post().uri("/internal/agent-worker/runs/" + id + "/heartbeat")
                .header("X-Claim-Token", "wrong").exchange().expectStatus().isUnauthorized();
    }

    @Test
    void heartbeatExtendsLeaseButNotActiveDeadlineAndClosesUnfinishedTools() {
        UUID document = document("tenant-a", "alice", DocumentVisibility.TENANT, "policy.txt");
        String id = create(document, null).path("runId").asText();
        JsonNode claim = claim();
        UUID invocation = UUID.randomUUID();
        runRepository.locked(UUID.fromString(id), run -> runRepository.startTool(run, invocation, "inspect_document",
                mapper.createObjectNode().put("documentId", document.toString()), null)).block();
        complete(claim, mapper.createObjectNode()).exchange().expectStatus().isEqualTo(409);
        for (int second : List.of(40, 80, 120, 160)) {
            clock.instant = Instant.parse(claim.path("deadlineAt").asText()).minusSeconds(180 - second);
            client.post().uri("/internal/agent-worker/runs/" + id + "/heartbeat")
                    .header("X-Claim-Token", claim.path("claimToken").asText()).exchange().expectStatus().isNoContent();
            get(id).exchange().expectStatus().isOk().expectBody().jsonPath("$.status").isEqualTo("RUNNING");
        }
        clock.instant = Instant.parse(claim.path("deadlineAt").asText());
        get(id).exchange().expectStatus().isOk().expectBody().jsonPath("$.errorCode").isEqualTo("RUN_DEADLINE");
        var call = runRepository.tool(UUID.fromString(id), invocation).block();
        assertThat(call.status()).isEqualTo("FAILED");
        assertThat(call.result().path("error").path("code").asText()).isEqualTo("RUN_TERMINATED");
    }

    @Test
    void toolTimeoutIsSavedAndOnlyOneLinkedRetryIsAllowed() {
        UUID document = document("tenant-a", "alice", DocumentVisibility.TENANT, "policy.txt");
        create(document, null);
        JsonNode claim = claim();
        properties.setToolTimeout(Duration.ofMillis(50));
        doReturn(Mono.never()).when(diagnostics).inspect(eq(document), any());
        UUID invocation = UUID.randomUUID();
        tool(claim, "inspect_document", document, invocation).exchange().expectStatus().isOk().expectBody()
                .jsonPath("$.status").isEqualTo("FAILED").jsonPath("$.error.retryable").isEqualTo(true)
                .jsonPath("$.error.code").isEqualTo("TRANSIENT_DEPENDENCY");
        UUID retry = UUID.randomUUID();
        retry(claim, document, retry, invocation).exchange().expectStatus().isOk().expectBody()
                .jsonPath("$.status").isEqualTo("FAILED");
        retry(claim, document, UUID.randomUUID(), invocation).exchange().expectStatus().isEqualTo(409);
        retry(claim, document, UUID.randomUUID(), retry).exchange().expectStatus().isEqualTo(409);
        tool(claim, "inspect_document", document, invocation).exchange().expectStatus().isOk().expectBody()
                .jsonPath("$.status").isEqualTo("FAILED");
        assertThat(runRepository.find(UUID.fromString(claim.path("runId").asText())).block().toolCount()).isEqualTo(2);
    }

    @Test
    void budgetsAndDisabledModeFailClearly() {
        UUID document = document("tenant-a", "alice", DocumentVisibility.TENANT, "policy.txt");
        create(document, null);
        JsonNode claim = claim();
        properties.setMaxTools(2);
        tool(claim, "inspect_document", document, UUID.randomUUID()).exchange().expectStatus().isOk();
        tool(claim, "list_ingestion_jobs", document, UUID.randomUUID()).exchange().expectStatus().isOk();
        tool(claim, "inspect_document", document, UUID.randomUUID()).exchange().expectStatus().isEqualTo(409)
                .expectBody().jsonPath("$.code").isEqualTo("BUDGET_EXCEEDED");
        properties.setEnabled(false);
        post("/api/v1/agent/runs").bodyValue(request(document)).exchange().expectStatus().isNotFound();
        client.post().uri("/internal/agent-worker/claims").header("X-Worker-Token", TOKEN)
                .exchange().expectStatus().isNotFound();
    }

    @Test
    void inspectsRealChunkEmbeddingAndUnsupportedTypeWithoutChangingThem() {
        UUID document = document("tenant-a", "alice", DocumentVisibility.TENANT, "policy.txt");
        var chunked = chunks.replaceChunks(document, chunker.chunk("Policies require review. ".repeat(80)), OffsetDateTime.now(clock)).block();
        embeddings.embedDocument(document, new RequestContext("tenant-a", "alice")).block();
        assertThat(diagnostics.inspect(document, new RequestContext("tenant-a", "alice")).block()
                .path("condition").asText()).isEqualTo("HEALTHY");
        db.sql("DELETE FROM child_chunk_embeddings WHERE child_chunk_id=:id")
                .bind("id", chunked.childChunks().get(0).id()).fetch().rowsUpdated().block();
        UUID unsupported = document("tenant-a", "alice", DocumentVisibility.TENANT, "policy.pdf");
        post("/api/v1/agent/runs").bodyValue(mapper.createObjectNode().put("question", "Inspect status")
                .set("documentIds", mapper.valueToTree(List.of(document, unsupported)))).exchange().expectStatus().isAccepted();
        JsonNode claim = claim();
        tool(claim, "inspect_document", document, UUID.randomUUID()).exchange().expectStatus().isOk().expectBody()
                .jsonPath("$.data.condition").isEqualTo("EMBEDDING_INCOMPLETE");
        tool(claim, "inspect_document", unsupported, UUID.randomUUID()).exchange().expectStatus().isOk().expectBody()
                .jsonPath("$.data.condition").isEqualTo("UNSUPPORTED_TYPE");
        db.sql("UPDATE child_chunk_embeddings SET model_name='different-model' WHERE document_id=:id")
                .bind("id", document).fetch().rowsUpdated().block();
        tool(claim, "inspect_document", document, UUID.randomUUID()).exchange().expectStatus().isOk().expectBody()
                .jsonPath("$.data.condition").isEqualTo("EMBEDDING_MODEL_MISMATCH");
        assertThat(chunks.findByDocumentId(document).block().childChunks()).isEqualTo(chunked.childChunks());
    }

    @Test
    void scriptedWorkerCompletesAgainstRealHttpAndPostgres(@TempDir Path temporary) throws Exception {
        Path worker = Path.of("workers/pi-worker/dist/src/main.js").toAbsolutePath();
        org.junit.jupiter.api.Assumptions.assumeTrue(Files.exists(worker), "Build the worker to run the cross-process smoke test");
        UUID document = document("tenant-a", "alice", DocumentVisibility.TENANT, "policy.txt");
        String id = create(document, null).path("runId").asText();
        Path output = temporary.resolve("worker.log");
        ProcessBuilder builder = new ProcessBuilder("node", worker.toString(), "--once").redirectErrorStream(true)
                .redirectOutput(output.toFile());
        builder.environment().put("NEXUS_AGENT_API_URL", "http://localhost:" + port);
        builder.environment().put("NEXUS_AGENT_WORKER_TOKEN", TOKEN);
        builder.environment().put("NEXUS_AGENT_WORKER_MODE", "scripted");
        Process process = builder.start();
        try {
            assertThat(process.waitFor(20, TimeUnit.SECONDS)).isTrue();
            assertThat(process.exitValue()).withFailMessage(Files.readString(output)).isZero();
            get(id).exchange().expectStatus().isOk().expectBody().jsonPath("$.status").isEqualTo("SUCCEEDED")
                    .jsonPath("$.report.findings[0].condition").isEqualTo("CHUNKING_REQUIRED");
            assertThat(Files.readString(output)).doesNotContain(TOKEN);
        } finally { if (process.isAlive()) { process.destroyForcibly().waitFor(); } }
    }

    @Test
    void demoScriptCreatesPollsAndPrintsThePersistedReport(@TempDir Path temporary) throws Exception {
        Path worker = Path.of("workers/pi-worker/dist/src/main.js").toAbsolutePath();
        org.junit.jupiter.api.Assumptions.assumeTrue(Files.exists(worker), "Build the worker before the demo smoke test");
        UUID document = document("tenant-a", "alice", DocumentVisibility.TENANT, "demo.txt");
        ProcessBuilder workerBuilder = new ProcessBuilder("node", worker.toString()).redirectErrorStream(true)
                .redirectOutput(temporary.resolve("worker.log").toFile());
        workerBuilder.environment().put("NEXUS_AGENT_API_URL", "http://localhost:" + port);
        workerBuilder.environment().put("NEXUS_AGENT_WORKER_TOKEN", TOKEN);
        workerBuilder.environment().put("NEXUS_AGENT_WORKER_MODE", "scripted");
        Process workerProcess = workerBuilder.start();
        Process demo = null;
        try {
            Path output = temporary.resolve("demo.log");
            ProcessBuilder demoBuilder = new ProcessBuilder("bash", "scripts/agent-demo.sh", document.toString())
                    .redirectErrorStream(true).redirectOutput(output.toFile());
            demoBuilder.environment().put("BASE_URL", "http://localhost:" + port);
            demoBuilder.environment().put("TENANT_ID", "tenant-a");
            demoBuilder.environment().put("ACTOR_ID", "alice");
            demo = demoBuilder.start();
            assertThat(demo.waitFor(20, TimeUnit.SECONDS)).isTrue();
            assertThat(demo.exitValue()).withFailMessage(Files.readString(output)).isZero();
            assertThat(Files.readString(output)).contains("SUCCEEDED", "CHUNKING_REQUIRED", "tool_completed")
                    .doesNotContain(TOKEN, "claimToken");
        } finally {
            if (demo != null && demo.isAlive()) { demo.destroyForcibly().waitFor(); }
            workerProcess.destroyForcibly().waitFor();
        }
    }

    @Test
    void recoveryPreservesCountersAndSnapshotButUsesANewAttemptAndClaim() {
        UUID document = document("tenant-a", "alice", DocumentVisibility.PRIVATE, "policy.txt");
        String id = create(document, null).path("runId").asText();
        JsonNode first = claim();
        JsonNode evidence = tool(first, "inspect_document", document, UUID.randomUUID()).exchange().expectStatus().isOk()
                .expectBody(JsonNode.class).returnResult().getResponseBody();
        client.post().uri("/internal/agent-worker/runs/" + id + "/model-rounds/" + UUID.randomUUID())
                .header("X-Claim-Token", first.path("claimToken").asText()).exchange().expectStatus().isNoContent();
        clock.instant = clock.instant.plusSeconds(46);
        JsonNode next = claim();
        assertThat(next.path("runId")).isEqualTo(first.path("runId"));
        assertThat(next.path("traceId")).isEqualTo(first.path("traceId"));
        assertThat(next.path("claimToken")).isNotEqualTo(first.path("claimToken"));
        assertThat(next.path("toolsUsed").asInt()).isEqualTo(1);
        assertThat(next.path("roundsUsed").asInt()).isEqualTo(1);
        assertThat(next.path("recoveryCount").asInt()).isEqualTo(1);
        assertThat(next.path("continuation").path("observations").get(0)).isEqualTo(evidence);
        assertThat(runRepository.find(UUID.fromString(id)).block().attempt()).isEqualTo(2);
        tool(first, "inspect_document", document, UUID.randomUUID()).exchange().expectStatus().isUnauthorized();
    }

    @Test
    void automaticRecoveryIsBoundedAndDoesNotResetTheToolBudget() {
        UUID document = document("tenant-a", "alice", DocumentVisibility.PRIVATE, "policy.txt");
        String id = create(document, null).path("runId").asText();
        claim();
        for (int n = 0; n < 3; n++) { clock.instant = clock.instant.plusSeconds(46); claim(); }
        clock.instant = clock.instant.plusSeconds(46);
        get(id).exchange().expectStatus().isOk().expectBody().jsonPath("$.status").isEqualTo("FAILED")
                .jsonPath("$.errorCode").isEqualTo("RECOVERY_EXHAUSTED").jsonPath("$.recoveryCount").isEqualTo(3);
        id = create(document, null).path("runId").asText();
        JsonNode next = claim();
        properties.setMaxTools(2);
        tool(next, "inspect_document", document, UUID.randomUUID()).exchange().expectStatus().isOk();
        clock.instant = clock.instant.plusSeconds(46);
        get(id).exchange().expectStatus().isOk().expectBody().jsonPath("$.errorCode").isEqualTo("BUDGET_EXCEEDED");
    }

    @Test
    void recoveryFailsClosedWhenDocumentAccessWasRevoked() {
        UUID document = document("tenant-a", "alice", DocumentVisibility.PRIVATE, "policy.txt");
        String id = create(document, null).path("runId").asText();
        claim();
        db.sql("UPDATE documents SET owner_id='bob' WHERE id=:id").bind("id", document).fetch().rowsUpdated().block();
        clock.instant = clock.instant.plusSeconds(46);
        get(id).exchange().expectStatus().isNotFound();
        assertThat(runRepository.find(UUID.fromString(id)).block().errorCode()).isEqualTo("ACCESS_REVOKED");
    }

    @Test
    void cancellationIsIdempotentOwnerBoundAndRejectsLateWorkerWrites() {
        UUID document = document("tenant-a", "alice", DocumentVisibility.PRIVATE, "policy.txt");
        String queued = create(document, null).path("runId").asText();
        client.post().uri("/api/v1/agent/runs/" + queued + "/cancel").header("X-Tenant-Id", "tenant-b")
                .header("X-Actor-Id", "alice").exchange().expectStatus().isNotFound();
        cancel(queued).expectStatus().isOk().expectBody().jsonPath("$.status").isEqualTo("CANCELLED");
        long sequence = runRepository.find(UUID.fromString(queued)).block().eventSequence();
        cancel(queued).expectStatus().isOk();
        assertThat(runRepository.find(UUID.fromString(queued)).block().eventSequence()).isEqualTo(sequence);
        String id = create(document, null).path("runId").asText();
        JsonNode running = claim();
        cancel(id).expectStatus().isOk().expectBody().jsonPath("$.cancellationPending").isEqualTo(false);
        tool(running, "inspect_document", document, UUID.randomUUID()).exchange().expectStatus().isEqualTo(409);
        complete(running, mapper.createObjectNode()).exchange().expectStatus().isEqualTo(409);
        get(id).exchange().expectStatus().isOk().expectBody().jsonPath("$.status").isEqualTo("CANCELLED");
    }

    @Test
    void sseReplaysMoreThanOnePageWithStableIdsAndValidatesCursorAndAccess() {
        UUID document = document("tenant-a", "alice", DocumentVisibility.PRIVATE, "policy.txt");
        JsonNode created = create(document, null);
        String id = created.path("runId").asText();
        runRepository.locked(UUID.fromString(id), run -> Flux.range(0, 105)
                .concatMap(n -> runRepository.event(run.id(), "test_progress", mapper.createObjectNode().put("number", n))).then()).block();
        cancel(id).expectStatus().isOk();
        long last = runRepository.find(UUID.fromString(id)).block().eventSequence();
        var events = stream(id, "", null).collectList().block(Duration.ofSeconds(10));
        assertThat(events).hasSize((int) last);
        for (int n = 0; n < events.size(); n++) {
            assertThat(events.get(n).id()).isEqualTo(Integer.toString(n + 1));
            assertThat(events.get(n).data().path("traceId")).isEqualTo(created.path("traceId"));
        }
        var tail = stream(id, "?afterSequence=0", "105").collectList().block(Duration.ofSeconds(10));
        assertThat(tail).hasSize((int) last - 105);
        assertThat(tail.get(0).id()).isEqualTo("106");
        for (String cursor : List.of("-1", "abc", "99999999999999999999", "9999")) {
            get(id + "/events/stream").header("Last-Event-ID", cursor).exchange().expectStatus().isBadRequest();
        }
        get(id + "/events/stream?afterSequence=-1").exchange().expectStatus().isBadRequest();
        client.get().uri("/api/v1/agent/runs/" + id + "/events/stream").header("X-Tenant-Id", "tenant-a")
                .header("X-Actor-Id", "bob").exchange().expectStatus().isNotFound();
        assertThat(events.toString()).doesNotContain(TOKEN, "claimToken", "minioObjectKey");
    }

    @Test
    void sseFollowsNewEventsAndDisconnectDoesNotCancelTheRun() {
        UUID document = document("tenant-a", "alice", DocumentVisibility.PRIVATE, "policy.txt");
        String id = create(document, null).path("runId").asText();
        JsonNode worker = claim();
        StepVerifier.create(stream(id, "", null).publishOn(Schedulers.boundedElastic()))
                .assertNext(event -> assertThat(event.id()).isEqualTo("1"))
                .assertNext(event -> assertThat(event.id()).isEqualTo("2"))
                .then(() -> tool(worker, "inspect_document", document, UUID.randomUUID()).exchange().expectStatus().isOk())
                .assertNext(event -> assertThat(event.id()).isEqualTo("3"))
                .assertNext(event -> assertThat(event.id()).isEqualTo("4"))
                .thenCancel().verify(Duration.ofSeconds(10));
        get(id).exchange().expectStatus().isOk().expectBody().jsonPath("$.status").isEqualTo("RUNNING");
        cancel(id).expectStatus().isOk();
        var replay = stream(id, "", "4").collectList().block(Duration.ofSeconds(10));
        assertThat(replay).extracting(ServerSentEvent::id).containsExactly("5", "6");
        assertThat(replay).extracting(ServerSentEvent::event).containsExactly("cancel_requested", "cancelled");
    }

    @Test
    void sseRechecksRevokedAccessDuringLivePollingAndClosesSafely() {
        UUID document = document("tenant-a", "alice", DocumentVisibility.PRIVATE, "policy.txt");
        String id = create(document, null).path("runId").asText();
        StepVerifier.create(stream(id, "", null).publishOn(Schedulers.boundedElastic()))
                .assertNext(event -> assertThat(event.id()).isEqualTo("1"))
                .then(() -> db.sql("UPDATE documents SET owner_id='bob' WHERE id=:id").bind("id", document).fetch().rowsUpdated().block())
                .assertNext(event -> {
                    assertThat(event.event()).isEqualTo("error");
                    assertThat(event.id()).isNull();
                    assertThat(event.data().path("code").asText()).isEqualTo("ACCESS_REVOKED");
                }).expectComplete().verify(Duration.ofSeconds(10));
        get(id + "/events/stream").exchange().expectStatus().isNotFound();
    }

    @Test
    void killedScriptedWorkerRecoversIntoFreshProcessOnTheSameRun(@TempDir Path temporary) throws Exception {
        UUID document = document("tenant-a", "alice", DocumentVisibility.PRIVATE, "policy.txt");
        JsonNode created = create(document, null);
        String id = created.path("runId").asText();
        Sinks.One<Boolean> entered = Sinks.one();
        doReturn(Mono.never().doOnSubscribe(ignored -> entered.tryEmitValue(true))).when(diagnostics).inspect(eq(document), any());
        Process first = startWorker(temporary.resolve("first.log"));
        try {
            assertThat(entered.asMono().block(Duration.ofSeconds(10))).isTrue();
        } finally { first.destroyForcibly().waitFor(); }
        clock.instant = clock.instant.plusSeconds(46);
        get(id).exchange().expectStatus().isOk().expectBody().jsonPath("$.status").isEqualTo("QUEUED");
        doCallRealMethod().when(diagnostics).inspect(eq(document), any());
        Path output = temporary.resolve("recovered.log");
        Process second = startWorker(output);
        try {
            assertThat(second.waitFor(20, TimeUnit.SECONDS)).isTrue();
            assertThat(second.exitValue()).withFailMessage(Files.readString(output)).isZero();
            get(id).exchange().expectStatus().isOk().expectBody().jsonPath("$.status").isEqualTo("SUCCEEDED")
                    .jsonPath("$.traceId").isEqualTo(created.path("traceId").asText()).jsonPath("$.recoveryCount").isEqualTo(1);
            assertThat(runRepository.find(UUID.fromString(id)).block().attempt()).isEqualTo(2);
            assertThat(chunks.findByDocumentId(document).block().childChunks()).isEmpty();
        } finally { if (second.isAlive()) { second.destroyForcibly().waitFor(); } }
    }

    @Test
    void browserObservationsArePaginatedScopedAndRestoreTheRequestWithoutWorkerSecrets() {
        UUID document = document("tenant-a", "alice", DocumentVisibility.PRIVATE, "browser-policy.txt");
        String id = create(document, null).path("runId").asText();
        JsonNode worker = claim();
        JsonNode observed = tool(worker, "inspect_document", document, UUID.randomUUID()).exchange().expectStatus().isOk()
                .expectBody(JsonNode.class).returnResult().getResponseBody();
        tool(worker, "list_ingestion_jobs", document, UUID.randomUUID()).exchange().expectStatus().isOk();
        get(id).exchange().expectStatus().isOk().expectHeader().valueEquals("Cache-Control", "no-store")
                .expectBody().jsonPath("$.question").isEqualTo(request(document).path("question").asText())
                .jsonPath("$.documentIds[0]").isEqualTo(document.toString());
        get(id + "/tools?limit=1").exchange().expectStatus().isOk().expectBody()
                .jsonPath("$.hasMore").isEqualTo(true).jsonPath("$.nextOffset").isEqualTo(1)
                .jsonPath("$.tools.length()").isEqualTo(1).jsonPath("$.tools[0].arguments").doesNotExist();
        get(id + "/tools?limit=1&offset=1").exchange().expectStatus().isOk().expectBody()
                .jsonPath("$.hasMore").isEqualTo(false).jsonPath("$.tools.length()").isEqualTo(1);
        String detailPath = id + "/tools/" + observed.path("observationId").asText();
        JsonNode detail = get(detailPath).exchange().expectStatus().isOk().expectHeader().valueEquals("Cache-Control", "no-store")
                .expectBody(JsonNode.class).returnResult().getResponseBody();
        assertThat(detail.path("result").path("data").path("condition").asText()).isEqualTo("CHUNKING_REQUIRED");
        assertThat(detail.path("arguments").path("documentId").asText()).isEqualTo(document.toString());
        assertThat(detail.toString()).doesNotContain("claimToken", "claim_hash", "argumentsHash", TOKEN, "minioObjectKey");
        for (String suffix : List.of("/tools", "/tools/" + observed.path("observationId").asText())) {
            for (RequestContext other : List.of(new RequestContext("tenant-b", "alice"), new RequestContext("tenant-a", "bob"))) {
                client.get().uri("/api/v1/agent/runs/" + id + suffix).header("X-Tenant-Id", other.tenantId())
                        .header("X-Actor-Id", other.actorId()).exchange().expectStatus().isNotFound();
            }
        }
        String anotherRun = create(document, null).path("runId").asText();
        get(anotherRun + "/tools/" + observed.path("observationId").asText()).exchange().expectStatus().isNotFound();
        db.sql("UPDATE documents SET owner_id='bob' WHERE id=:id").bind("id", document).fetch().rowsUpdated().block();
        get(detailPath).exchange().expectStatus().isNotFound();
        get(id + "/tools").exchange().expectStatus().isNotFound();
    }

    @Test
    void browserObservationRoutesRejectMissingIdentityAndInvalidPagination() {
        String id = create(document("tenant-a", "alice", DocumentVisibility.TENANT, "browser.txt"), null).path("runId").asText();
        client.get().uri("/api/v1/agent/runs/" + id + "/tools").exchange().expectStatus().isBadRequest();
        client.get().uri("/api/v1/agent/runs/" + id + "/tools/" + UUID.randomUUID()).exchange().expectStatus().isBadRequest();
        for (String query : List.of("limit=0", "limit=51", "offset=-1", "limit=abc")) {
            get(id + "/tools?" + query).exchange().expectStatus().isBadRequest();
        }
    }

    private Process startWorker(Path output) throws Exception {
        Path path = Path.of("workers/pi-worker/dist/src/main.js").toAbsolutePath();
        assertThat(path).exists();
        var builder = new ProcessBuilder("node", path.toString(), "--once").redirectErrorStream(true).redirectOutput(output.toFile());
        builder.environment().put("NEXUS_AGENT_API_URL", "http://localhost:" + port);
        builder.environment().put("NEXUS_AGENT_WORKER_TOKEN", TOKEN);
        builder.environment().put("NEXUS_AGENT_WORKER_MODE", "scripted");
        return builder.start();
    }

    private WebTestClient.ResponseSpec cancel(String id) { return post("/api/v1/agent/runs/" + id + "/cancel").exchange(); }

    private Flux<ServerSentEvent<JsonNode>> stream(String id, String query, String lastEventId) {
        var request = get(id + "/events/stream" + query).accept(MediaType.TEXT_EVENT_STREAM);
        if (lastEventId != null) { request.header("Last-Event-ID", lastEventId); }
        return request.exchange().expectStatus().isOk().expectHeader().contentTypeCompatibleWith(MediaType.TEXT_EVENT_STREAM)
                .expectHeader().valueEquals("Cache-Control", "no-store")
                .returnResult(new ParameterizedTypeReference<ServerSentEvent<JsonNode>>() { }).getResponseBody()
                .filter(event -> event.data() != null);
    }

    private UUID document(String tenant, String actor, DocumentVisibility visibility, String filename) {
        UUID id = UUID.randomUUID();
        documents.save(DocumentMetadata.stored(id, tenant, actor, visibility, filename,
                filename.endsWith("pdf") ? "application/pdf" : "text/plain", 42, "a".repeat(64),
                "test-bucket", "documents/" + id, OffsetDateTime.now(clock))).block();
        return id;
    }

    private void insertFailedJob(UUID document) {
        db.sql("""
                INSERT INTO ingestion_jobs(id, document_id, tenant_id, job_type, status, error_message, created_at, updated_at)
                VALUES (:id, :document, 'tenant-a', 'CHUNK', 'FAILED', 'raw-secret should never reach the model', :now, :now)
                """).bind("id", UUID.randomUUID()).bind("document", document).bind("now", OffsetDateTime.now(clock))
                .fetch().rowsUpdated().block();
    }

    private ObjectNode request(UUID document) {
        return mapper.createObjectNode().put("question", "Inspect this document").set("documentIds", mapper.valueToTree(List.of(document)));
    }

    private JsonNode create(UUID document, String key) {
        var request = post("/api/v1/agent/runs");
        if (key != null) { request.header("Idempotency-Key", key); }
        return request.bodyValue(request(document)).exchange().expectStatus().isAccepted()
                .expectBody(JsonNode.class).returnResult().getResponseBody();
    }

    private JsonNode claim() {
        return client.post().uri("/internal/agent-worker/claims").header("X-Worker-Token", TOKEN).exchange()
                .expectStatus().isOk().expectBody(JsonNode.class).returnResult().getResponseBody();
    }

    private WebTestClient.RequestBodySpec post(String uri) {
        return client.post().uri(uri).header("X-Tenant-Id", "tenant-a").header("X-Actor-Id", "alice");
    }

    private WebTestClient.RequestHeadersSpec<?> get(String id) {
        return client.get().uri("/api/v1/agent/runs/" + id).header("X-Tenant-Id", "tenant-a").header("X-Actor-Id", "alice");
    }

    private WebTestClient.RequestHeadersSpec<?> tool(JsonNode claim, String name, UUID document, UUID invocation) {
        ObjectNode body = mapper.createObjectNode().put("schemaVersion", 1).put("invocationId", invocation.toString()).put("toolName", name);
        body.set("arguments", mapper.createObjectNode().put("documentId", document.toString()));
        return client.post().uri("/internal/agent-worker/runs/" + claim.path("runId").asText() + "/tools")
                .header("X-Claim-Token", claim.path("claimToken").asText()).bodyValue(body);
    }

    private WebTestClient.RequestHeadersSpec<?> complete(JsonNode claim, JsonNode report) {
        return client.post().uri("/internal/agent-worker/runs/" + claim.path("runId").asText() + "/complete")
                .header("X-Claim-Token", claim.path("claimToken").asText()).bodyValue(report);
    }

    private WebTestClient.RequestHeadersSpec<?> retry(JsonNode claim, UUID document, UUID invocation, UUID retryOf) {
        ObjectNode body = mapper.createObjectNode().put("schemaVersion", 1).put("invocationId", invocation.toString())
                .put("toolName", "inspect_document").put("retryOf", retryOf.toString());
        body.set("arguments", mapper.createObjectNode().put("documentId", document.toString()));
        return client.post().uri("/internal/agent-worker/runs/" + claim.path("runId").asText() + "/tools")
                .header("X-Claim-Token", claim.path("claimToken").asText()).bodyValue(body);
    }

    private ObjectNode report(UUID document, JsonNode inspect, JsonNode jobs) {
        ObjectNode finding = mapper.createObjectNode().put("documentId", document.toString()).put("condition", "CHUNKING_REQUIRED")
                .put("explanation", "No chunks are currently stored.");
        finding.set("observationIds", mapper.valueToTree(List.of(inspect.path("observationId").asText(), jobs.path("observationId").asText())));
        ObjectNode report = mapper.createObjectNode().put("schemaVersion", 1).put("summary", "Read-only diagnosis");
        report.set("findings", mapper.valueToTree(List.of(finding)));
        report.set("unresolved", mapper.createArrayNode());
        return report;
    }

    static class MutableClock extends Clock {
        volatile Instant instant = Instant.now();
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return instant; }
    }

    @TestConfiguration
    static class TestClockConfig {
        @Bean @Primary MutableClock agentTestClock() { return new MutableClock(); }
    }
}
