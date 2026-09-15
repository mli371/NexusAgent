package com.nexusagent.agentrun;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.never;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.nexusagent.agentrun.application.AgentRunProperties;
import com.nexusagent.agentrun.application.AgentRunService;
import com.nexusagent.agentrun.approval.ApprovalService;
import com.nexusagent.agentrun.approval.ApprovalRepository;
import com.nexusagent.agentrun.approval.ApprovedRetryService;
import com.nexusagent.agentrun.repository.AgentRunRepository;
import com.nexusagent.chunking.application.DocumentTextExtractionService;
import com.nexusagent.chunking.application.ParentChildChunker;
import com.nexusagent.chunking.domain.ExtractedDocumentText;
import com.nexusagent.chunking.repository.ChunkRepository;
import com.nexusagent.common.context.RequestContext;
import com.nexusagent.documents.domain.DocumentMetadata;
import com.nexusagent.documents.domain.DocumentVisibility;
import com.nexusagent.documents.repository.DocumentRepository;
import com.nexusagent.embeddings.application.ChildChunkEmbeddingService;
import com.nexusagent.enterprise.ingestion.IngestionFailure;
import com.nexusagent.enterprise.ingestion.IngestionJobRepository;
import com.nexusagent.enterprise.ingestion.IngestionJobType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.r2dbc.core.DatabaseClient;
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

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "nexus.agent.enabled=true", "nexus.agent.worker-token=integration-test-worker-token-32-characters",
        "nexus.agent.worker-mode=scripted", "nexus.redis.enabled=false"
})
@Import(AgentRunIntegrationTest.TestClockConfig.class)
@Testcontainers(disabledWithoutDocker = true)
class AgentApprovalIntegrationTest {
    private static final String TOKEN = "integration-test-worker-token-32-characters";
    private static final RequestContext OWNER = new RequestContext("tenant-a", "alice");
    @Container static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(
            DockerImageName.parse("pgvector/pgvector:pg16").asCompatibleSubstituteFor("postgres"));
    @DynamicPropertySource static void database(DynamicPropertyRegistry registry) {
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
    @Autowired AgentRunRepository repository;
    @Autowired ApprovalService approvals;
    @Autowired ApprovalRepository approvalRepository;
    @Autowired ApprovedRetryService retries;
    @Autowired AgentRunProperties properties;
    @Autowired ObjectMapper mapper;
    @Autowired AgentRunIntegrationTest.MutableClock clock;
    @MockBean DocumentTextExtractionService extraction;
    @SpyBean IngestionJobRepository jobs;
    @LocalServerPort int port;
    WebTestClient client;

    @BeforeEach void setup() {
        clock.instant = Instant.now();
        properties.setMaxTools(30);
        properties.setMaxRounds(12);
        properties.setMaxRecoveries(3);
        db.sql("TRUNCATE agent_runs CASCADE").fetch().rowsUpdated().block();
        client = WebTestClient.bindToServer().baseUrl("http://localhost:" + port).responseTimeout(Duration.ofSeconds(15)).build();
        when(extraction.extract(any())).thenAnswer(call -> Mono.just(new ExtractedDocumentText(
                ((DocumentMetadata) call.getArgument(0)).id(), "A synthetic policy requires review. ".repeat(40), "text/plain")));
    }

    @Test void approvalPausesWithoutWritesAndTwoSeparateActionsCompleteWithExactJobLinks() {
        UUID document = document("policy.txt");
        JsonNode first = start(document);
        String approval = propose(first, document, "CHUNK").path("data").path("approvalId").asText();
        assertThat(view(first).path("status").asText()).isEqualTo("WAITING_APPROVAL");
        assertThat(count("ingestion_jobs", document)).isZero();
        assertThat(chunks.findByDocumentId(document).block().childChunks()).isEmpty();
        decision(first, approval, "APPROVE", OWNER).expectStatus().isOk();
        decision(first, approval, "APPROVE", OWNER).expectStatus().isOk();
        JsonNode resumed = claim();
        assertThat(resumed.path("pendingApprovalId").asText()).isEqualTo(approval);
        assertThat(resumed.path("toolsUsed").asInt()).isEqualTo(3);
        JsonNode result = execute(resumed, approval);
        assertThat(result.path("status").asText()).isEqualTo("SUCCEEDED");
        assertThat(execute(resumed, approval)).isEqualTo(result);
        assertThat(count("ingestion_jobs", document)).isEqualTo(1);
        assertThat(count("child_chunks", document)).isPositive();
        assertThat(count("child_chunk_embeddings", document)).isZero();
        assertThat(db.sql("SELECT trace_id FROM ingestion_jobs WHERE id=:id")
                .bind("id", UUID.fromString(result.path("ingestionJobId").asText()))
                .map(row -> row.get("trace_id", String.class)).one().block()).isEqualTo(first.path("traceId").asText());
        assertThat(db.sql("SELECT count(*) AS n FROM audit_events WHERE document_id=:id AND trace_id=:trace")
                .bind("id", document).bind("trace", first.path("traceId").asText()).map(row -> row.get("n", Long.class)).one().block()).isPositive();
        clock.instant = clock.instant.plusSeconds(1);
        String secondApproval = propose(resumed, document, "EMBED_MISSING").path("data").path("approvalId").asText();
        assertThat(secondApproval).isNotEqualTo(approval);
        decision(resumed, secondApproval, "APPROVE", OWNER).expectStatus().isOk();
        JsonNode finalClaim = claim();
        assertThat(execute(finalClaim, secondApproval).path("status").asText()).isEqualTo("SUCCEEDED");
        assertThat(count("child_chunk_embeddings", document)).isEqualTo(count("child_chunks", document));
        assertThat(count("ingestion_jobs", document)).isEqualTo(2);
        assertThat(view(finalClaim).path("actionResults")).hasSize(2);
    }

    @Test void differentActorsTenantsAndClaimTokensCannotApproveOrOverrideAnAction() {
        UUID document = document("private.txt");
        JsonNode run = start(document);
        String approval = propose(run, document, "CHUNK").path("data").path("approvalId").asText();
        decision(run, approval, "APPROVE", new RequestContext("tenant-b", "alice")).expectStatus().isNotFound();
        decision(run, approval, "APPROVE", new RequestContext("tenant-a", "bob")).expectStatus().isNotFound();
        client.post().uri(publicApproval(run, approval)).header("X-Claim-Token", run.path("claimToken").asText())
                .bodyValue(mapper.createObjectNode().put("decision", "APPROVE")).exchange().expectStatus().isBadRequest();
        client.post().uri(publicApproval(run, approval)).header("X-Tenant-Id", "tenant-a").header("X-Actor-Id", "alice")
                .bodyValue(mapper.createObjectNode().put("decision", "APPROVE").put("action", "FORCE_RECHUNK"))
                .exchange().expectStatus().isBadRequest();
        internal(run, "/approved-retries/" + approval).exchange().expectStatus().isEqualTo(409);
        assertThat(count("ingestion_jobs", document)).isZero();
        db.sql("UPDATE documents SET owner_id='bob' WHERE id=:id").bind("id", document).fetch().rowsUpdated().block();
        decision(run, approval, "APPROVE", OWNER).expectStatus().isNotFound();
    }

    @Test void rejectionAndExpiryCancelWithoutMutation() {
        UUID document = document("policy.txt");
        JsonNode first = start(document);
        String approval = propose(first, document, "CHUNK").path("data").path("approvalId").asText();
        decision(first, approval, "REJECT", OWNER).expectStatus().isOk();
        decision(first, approval, "REJECT", OWNER).expectStatus().isOk();
        decision(first, approval, "APPROVE", OWNER).expectStatus().isEqualTo(409);
        assertThat(view(first).path("errorCode").asText()).isEqualTo("APPROVAL_REJECTED");
        JsonNode second = start(document);
        String expired = propose(second, document, "CHUNK").path("data").path("approvalId").asText();
        clock.instant = clock.instant.plusSeconds(1801);
        assertThat(view(second).path("status").asText()).isEqualTo("CANCELLED");
        assertThat(view(second).path("errorCode").asText()).isEqualTo("APPROVAL_EXPIRED");
        decision(second, expired, "APPROVE", OWNER).expectStatus().isEqualTo(409);
        assertThat(count("ingestion_jobs", document)).isZero();
    }

    @Test void changedFingerprintInvalidatesApprovalWithoutDispatch() {
        UUID document = document("policy.txt");
        JsonNode run = start(document);
        String approval = propose(run, document, "CHUNK").path("data").path("approvalId").asText();
        decision(run, approval, "APPROVE", OWNER).expectStatus().isOk();
        db.sql("UPDATE documents SET sha256=:hash WHERE id=:id").bind("id", document).bind("hash", "b".repeat(64))
                .fetch().rowsUpdated().block();
        JsonNode result = execute(claim(), approval);
        assertThat(result.path("status").asText()).isEqualTo("FAILED");
        assertThat(result.path("errorCode").asText()).isEqualTo("DOCUMENT_STATE_CHANGED");
        assertThat(result.path("ingestionJobId").isNull()).isTrue();
        assertThat(count("ingestion_jobs", document)).isZero();
    }

    @Test void alreadyCompletedActionIsSkippedAndNeverRegeneratesStableChunks() {
        UUID document = document("policy.txt");
        JsonNode run = start(document);
        String approval = propose(run, document, "CHUNK").path("data").path("approvalId").asText();
        decision(run, approval, "APPROVE", OWNER).expectStatus().isOk();
        var before = chunks.replaceChunks(document, chunker.chunk("Existing child evidence. ".repeat(40)), OffsetDateTime.now(clock)).block();
        JsonNode result = execute(claim(), approval);
        assertThat(result.path("status").asText()).isEqualTo("SKIPPED");
        assertThat(chunks.findByDocumentId(document).block()).isEqualTo(before);
        assertThat(count("ingestion_jobs", document)).isZero();
    }

    @Test void duplicateConcurrentApprovalAndExecutionReserveOnlyOneMutation() {
        UUID document = document("policy.txt");
        JsonNode run = start(document);
        String approval = propose(run, document, "CHUNK").path("data").path("approvalId").asText();
        UUID id = UUID.fromString(run.path("runId").asText());
        UUID approvalId = UUID.fromString(approval);
        assertThat(Flux.merge(approvals.decide(id, approvalId, "APPROVE", OWNER), approvals.decide(id, approvalId, "APPROVE", OWNER))
                .collectList().block()).hasSize(2);
        JsonNode resumed = claim();
        List<JsonNode> outcomes = Flux.merge(retries.execute(id, resumed.path("claimToken").asText(), approvalId),
                retries.execute(id, resumed.path("claimToken").asText(), approvalId)).collectList().block();
        assertThat(outcomes).hasSize(2);
        assertThat(count("ingestion_jobs", document)).isEqualTo(1);
        assertThat(execute(resumed, approval).path("status").asText()).isEqualTo("SUCCEEDED");
    }

    @Test void proposalReplayAfterResponseLossReturnsTheSameApproval() {
        UUID document = document("policy.txt");
        JsonNode run = start(document);
        inspect(run, document);
        ObjectNode body = proposalBody(document, "CHUNK");
        JsonNode first = call(run, body);
        assertThat(call(run, body)).isEqualTo(first);
        assertThat(view(run).path("approvals")).hasSize(1);
        ((ObjectNode) body.get("arguments")).put("reason", "different");
        internal(run, "/tools").bodyValue(body).exchange().expectStatus().isEqualTo(409);
    }

    @Test void policyRejectsUnsupportedTypesUnknownFailuresEmptyTextAndRunningJobs() {
        for (String scenario : List.of("pdf", "UNKNOWN", "EMPTY_TEXT", "RUNNING")) {
            UUID document = document(scenario.equals("pdf") ? "policy.pdf" : "policy.txt");
            if (!scenario.equals("pdf")) { job(document, scenario.equals("RUNNING") ? "RUNNING" : "FAILED", scenario); }
            JsonNode run = start(document);
            JsonNode proposal = propose(run, document, "CHUNK");
            assertThat(proposal.path("status").asText()).isEqualTo("FAILED");
            assertThat(proposal.path("error").path("retryable").asBoolean()).isFalse();
            assertThat(view(run).path("approvals")).isEmpty();
            internal(run, "/fail").bodyValue(mapper.createObjectNode().put("code", "TOOL_FAILED"))
                    .exchange().expectStatus().isNoContent();
        }
    }

    @Test void transientFailureIsEligibleButMismatchedEmbeddingsAreNot() {
        UUID document = document("policy.txt");
        job(document, "FAILED", "TRANSIENT_DEPENDENCY");
        JsonNode run = start(document);
        String approval = propose(run, document, "CHUNK").path("data").path("approvalId").asText();
        decision(run, approval, "REJECT", OWNER).expectStatus().isOk();
        chunks.replaceChunks(document, chunker.chunk("Policy evidence. ".repeat(40)), OffsetDateTime.now(clock)).block();
        embeddings.embedDocument(document, OWNER).block();
        db.sql("UPDATE child_chunk_embeddings SET model_name='other' WHERE document_id=:id").bind("id", document).fetch().rowsUpdated().block();
        JsonNode other = start(document);
        assertThat(propose(other, document, "EMBED_MISSING").path("error").path("message").asText())
                .contains("EMBEDDING_MODEL_MISMATCH");
    }

    @Test void knownExecutionFailureIsSavedAndConsumedApprovalCannotRetryIt() {
        UUID document = document("empty.txt");
        doReturn(Mono.just(new ExtractedDocumentText(document, " ", "text/plain"))).when(extraction).extract(any());
        JsonNode run = start(document);
        String approval = propose(run, document, "CHUNK").path("data").path("approvalId").asText();
        decision(run, approval, "APPROVE", OWNER).expectStatus().isOk();
        JsonNode resumed = claim();
        JsonNode result = execute(resumed, approval);
        assertThat(result.path("status").asText()).isEqualTo("FAILED");
        assertThat(result.path("errorCode").asText()).isEqualTo("EMPTY_TEXT");
        assertThat(execute(resumed, approval)).isEqualTo(result);
        assertThat(count("ingestion_jobs", document)).isEqualTo(1);
        assertThat(propose(resumed, document, "CHUNK").path("status").asText()).isEqualTo("FAILED");
    }

    @Test void leaseLossDuringIngestionIsUnknownAndNeverReplayed() {
        UUID document = document("policy.txt");
        JsonNode run = start(document);
        String approval = propose(run, document, "CHUNK").path("data").path("approvalId").asText();
        decision(run, approval, "APPROVE", OWNER).expectStatus().isOk();
        JsonNode resumed = claim();
        Sinks.One<ExtractedDocumentText> io = Sinks.one();
        Sinks.One<Boolean> entered = Sinks.one();
        doReturn(io.asMono().doOnSubscribe(ignored -> entered.tryEmitValue(true))).when(extraction).extract(any());
        var future = retries.execute(UUID.fromString(run.path("runId").asText()), resumed.path("claimToken").asText(), UUID.fromString(approval)).toFuture();
        try {
            entered.asMono().block(Duration.ofSeconds(5));
            // A second connection can read the committed job while I/O is pending: no long transaction.
            assertThat(count("ingestion_jobs", document)).isEqualTo(1);
            clock.instant = clock.instant.plusSeconds(46);
            assertThat(view(resumed).path("errorCode").asText()).isEqualTo("ACTION_OUTCOME_UNKNOWN");
            io.tryEmitValue(new ExtractedDocumentText(document, "Synthetic evidence", "text/plain"));
            JsonNode late = future.join();
            assertThat(late.path("status").asText()).isEqualTo("SUCCEEDED");
            assertThat(late.path("runStatus").asText()).isEqualTo("RECOVERY_REQUIRED");
            internal(resumed, "/approved-retries/" + approval).exchange().expectStatus().isEqualTo(409);
            assertThat(count("ingestion_jobs", document)).isEqualTo(1);
            assertThat(view(resumed).path("status").asText()).isEqualTo("QUEUED");
            assertThat(claim().hasNonNull("pendingApprovalId")).isFalse();
        } finally { io.tryEmitError(new IngestionFailure("UNKNOWN", "test ended")); future.cancel(true); }
    }

    @Test void modelRoundReservationsAreIdempotentAndSurviveApprovalContinuation() {
        UUID document = document("policy.txt");
        JsonNode run = start(document);
        UUID reservation = UUID.randomUUID();
        internal(run, "/model-rounds/" + reservation).exchange().expectStatus().isNoContent();
        internal(run, "/model-rounds/" + reservation).exchange().expectStatus().isNoContent();
        String approval = propose(run, document, "CHUNK").path("data").path("approvalId").asText();
        decision(run, approval, "APPROVE", OWNER).expectStatus().isOk();
        JsonNode resumed = claim();
        assertThat(resumed.path("roundsUsed").asInt()).isEqualTo(1);
        assertThat(resumed.path("toolsUsed").asInt()).isEqualTo(3);
        properties.setMaxRounds(1);
        internal(resumed, "/model-rounds/" + UUID.randomUUID()).exchange().expectStatus().isEqualTo(409)
                .expectBody().jsonPath("$.code").isEqualTo("BUDGET_EXCEEDED");
    }

    @Test void actualScriptedWorkerPausesTwiceThenReinspectsAndCompletes(@TempDir Path temporary) throws Exception {
        UUID document = document("demo.txt");
        JsonNode created = runs.create(request(document, "Approve processing: inspect and propose missing processing"), OWNER, null, null).block();
        worker(temporary);
        for (String action : List.of("CHUNK", "EMBED_MISSING")) {
            JsonNode waiting = view(created);
            assertThat(waiting.path("status").asText()).isEqualTo("WAITING_APPROVAL");
            assertThat(waiting.path("pendingApproval").path("action").asText()).isEqualTo(action);
            decision(created, waiting.path("pendingApproval").path("approvalId").asText(), "APPROVE", OWNER).expectStatus().isOk();
            clock.instant = Instant.now();
            worker(temporary);
        }
        JsonNode complete = view(created);
        assertThat(complete.path("status").asText()).isEqualTo("SUCCEEDED");
        assertThat(complete.path("report").path("findings").get(0).path("condition").asText()).isEqualTo("HEALTHY");
        assertThat(complete.path("actionResults")).hasSize(2);
        assertThat(count("ingestion_jobs", document)).isEqualTo(2);
        assertThat(complete.toString()).doesNotContain("Synthetic policy requires review", TOKEN, "claimToken", "minioObjectKey");
    }

    @Test void revokedAccessAfterApprovalStillPreventsDispatch() {
        UUID document = document("private.txt");
        JsonNode run = start(document);
        String approval = propose(run, document, "CHUNK").path("data").path("approvalId").asText();
        decision(run, approval, "APPROVE", OWNER).expectStatus().isOk();
        JsonNode resumed = claim();
        db.sql("UPDATE documents SET owner_id='bob' WHERE id=:id").bind("id", document).fetch().rowsUpdated().block();
        internal(resumed, "/approved-retries/" + approval).exchange().expectStatus().isNotFound();
        assertThat(count("ingestion_jobs", document)).isZero();
        verify(extraction, never()).extract(any());
    }

    @Test void jobReservationFailureRollsBackExecutionReservationBeforeAnyBusinessIo() {
        UUID document = document("policy.txt");
        JsonNode run = start(document);
        String approval = propose(run, document, "CHUNK").path("data").path("approvalId").asText();
        decision(run, approval, "APPROVE", OWNER).expectStatus().isOk();
        JsonNode resumed = claim();
        doReturn(Mono.error(new org.springframework.dao.DataAccessResourceFailureException("test storage failure")))
                .when(jobs).createLinked(any(), any(), any(), any(), any(), any());
        internal(resumed, "/approved-retries/" + approval).exchange().expectStatus().isEqualTo(503);
        assertThat(view(resumed).path("actionResults").get(0).path("status").asText()).isEqualTo("PENDING");
        assertThat(count("ingestion_jobs", document)).isZero();
        verify(extraction, never()).extract(any());
    }

    @Test void failedJobBookkeepingDoesNotPretendBusinessWritesWereRolledBack() {
        UUID document = document("policy.txt");
        JsonNode run = start(document);
        String approval = propose(run, document, "CHUNK").path("data").path("approvalId").asText();
        decision(run, approval, "APPROVE", OWNER).expectStatus().isOk();
        JsonNode resumed = claim();
        doReturn(Mono.error(new org.springframework.dao.DataAccessResourceFailureException("status storage failure")))
                .when(jobs).markSucceeded(any(), any());
        doReturn(Mono.error(new org.springframework.dao.DataAccessResourceFailureException("status storage failure")))
                .when(jobs).markFailed(any(), any(), any(), any());
        JsonNode result = execute(resumed, approval);
        assertThat(result.path("status").asText()).isEqualTo("UNKNOWN");
        assertThat(db.sql("SELECT count(*) AS n FROM agent_action_executions WHERE run_id=:id AND finished_at IS NULL")
                .bind("id", id(resumed)).map(row -> row.get("n", Long.class)).one().block()).isEqualTo(1);
        assertThat(count("child_chunks", document)).isPositive();
        assertThat(count("ingestion_jobs", document)).isEqualTo(1);
        assertThat(view(resumed).path("errorCode").asText()).isEqualTo("ACTION_OUTCOME_UNKNOWN");
        internal(resumed, "/approved-retries/" + approval).exchange().expectStatus().isEqualTo(409);
    }

    @Test void embeddingCoverageAndChunkIdentityChangesInvalidatePendingEmbeddingApproval() {
        UUID document = document("policy.txt");
        chunks.replaceChunks(document, chunker.chunk("Same text different identity. ".repeat(40)), OffsetDateTime.now(clock)).block();
        JsonNode run = start(document);
        String approval = propose(run, document, "EMBED_MISSING").path("data").path("approvalId").asText();
        decision(run, approval, "APPROVE", OWNER).expectStatus().isOk();
        chunks.replaceChunks(document, chunker.chunk("Same text different identity. ".repeat(40)), OffsetDateTime.now(clock)).block();
        JsonNode result = execute(claim(), approval);
        assertThat(result.path("errorCode").asText()).isEqualTo("DOCUMENT_STATE_CHANGED");
        assertThat(count("child_chunk_embeddings", document)).isZero();
        assertThat(count("ingestion_jobs", document)).isZero();
    }

    @Test void cancelWhileApprovalWaitsOrBeforeDispatchNeverStartsTheWrite() {
        UUID document = document("policy.txt");
        JsonNode run = start(document);
        String approval = propose(run, document, "CHUNK").path("data").path("approvalId").asText();
        cancel(run).expectStatus().isOk().expectBody().jsonPath("$.status").isEqualTo("CANCELLED");
        assertThat(view(run).path("approvals").get(0).path("status").asText()).isEqualTo("CANCELLED");
        decision(run, approval, "APPROVE", OWNER).expectStatus().isEqualTo(409);
        run = start(document);
        approval = propose(run, document, "CHUNK").path("data").path("approvalId").asText();
        decision(run, approval, "APPROVE", OWNER).expectStatus().isOk();
        cancel(run).expectStatus().isOk();
        assertThat(view(run).path("actionResults").get(0).path("status").asText()).isEqualTo("SKIPPED");
        assertThat(count("ingestion_jobs", document)).isZero();
        verify(extraction, never()).extract(any());
    }

    @Test void cancelDuringBusinessIoAcknowledgesPendingThenRecordsKnownOutcome() {
        UUID document = document("policy.txt");
        JsonNode run = start(document);
        String approval = propose(run, document, "CHUNK").path("data").path("approvalId").asText();
        decision(run, approval, "APPROVE", OWNER).expectStatus().isOk();
        JsonNode resumed = claim();
        Sinks.One<ExtractedDocumentText> io = Sinks.one();
        Sinks.One<Boolean> entered = Sinks.one();
        doReturn(io.asMono().doOnSubscribe(ignored -> entered.tryEmitValue(true))).when(extraction).extract(any());
        var future = retries.execute(id(run), resumed.path("claimToken").asText(), UUID.fromString(approval)).toFuture();
        try {
            entered.asMono().block(Duration.ofSeconds(5));
            cancel(run).expectStatus().isOk().expectBody().jsonPath("$.status").isEqualTo("RUNNING")
                    .jsonPath("$.cancellationRequested").isEqualTo(true).jsonPath("$.cancellationPending").isEqualTo(true);
            internal(resumed, "/heartbeat").exchange().expectStatus().isNoContent();
            internal(resumed, "/approved-retries/" + approval).exchange().expectStatus().isEqualTo(409);
            io.tryEmitValue(new ExtractedDocumentText(document, "Synthetic approved content", "text/plain"));
            JsonNode result = future.join();
            assertThat(result.path("status").asText()).isEqualTo("SUCCEEDED");
            assertThat(result.path("runStatus").asText()).isEqualTo("CANCELLED");
            assertThat(view(run).path("cancellationPending").asBoolean()).isFalse();
            assertThat(count("child_chunks", document)).isPositive();
            assertThat(count("ingestion_jobs", document)).isEqualTo(1);
            internal(resumed, "/fail").bodyValue(mapper.createObjectNode().put("code", "WORKER_ERROR"))
                    .exchange().expectStatus().isNoContent();
            assertThat(view(run).path("status").asText()).isEqualTo("CANCELLED");
        } finally { io.tryEmitError(new IngestionFailure("UNKNOWN", "test ended")); future.cancel(true); }
    }

    @Test void unknownWriteWaitsForItsExactJobNotAnUnrelatedSuccessfulJob() {
        UUID document = document("policy.txt");
        Held held = reserveWithoutDispatch(document);
        clock.instant = clock.instant.plusSeconds(46);
        assertThat(view(held.run()).path("status").asText()).isEqualTo("RECOVERY_REQUIRED");
        chunks.replaceChunks(document, chunker.chunk("Synthetic evidence"), OffsetDateTime.now(clock)).block();
        job(document, "SUCCEEDED", "UNKNOWN");
        assertThat(view(held.run()).path("status").asText()).isEqualTo("RECOVERY_REQUIRED");
        internal(held.run(), "/approved-retries/" + held.approval()).exchange().expectStatus().isEqualTo(409);
        client.post().uri("/internal/agent-worker/claims").header("X-Worker-Token", TOKEN).exchange().expectStatus().isNoContent();
        jobs.markSucceeded(held.job(), OffsetDateTime.now(clock)).block();
        assertThat(view(held.run()).path("status").asText()).isEqualTo("QUEUED");
        JsonNode resumed = claim();
        assertThat(resumed.hasNonNull("pendingApprovalId")).isFalse();
        assertThat(resumed.path("continuation").path("actionResults").get(0).path("status").asText()).isEqualTo("SUCCEEDED");
        assertThat(count("ingestion_jobs", document)).isEqualTo(2);
        assertThat(repository.events(id(held.run()), 0).filter(e -> e.path("eventType").asText().equals("action_reconciled"))
                .count().block()).isEqualTo(1);
        verify(extraction, never()).extract(any());
    }

    @Test void successfulJobWithoutMatchingCurrentBusinessStateRemainsUnresolved() {
        UUID document = document("policy.txt");
        Held held = reserveWithoutDispatch(document);
        clock.instant = clock.instant.plusSeconds(46);
        view(held.run());
        jobs.markSucceeded(held.job(), OffsetDateTime.now(clock)).block();
        assertThat(view(held.run()).path("status").asText()).isEqualTo("RECOVERY_REQUIRED");
        cancel(held.run()).expectStatus().isOk().expectBody().jsonPath("$.cancellationPending").isEqualTo(true);
        assertThat(view(held.run()).path("status").asText()).isEqualTo("RECOVERY_REQUIRED");
    }

    @Test void knownFailedJobResolvesPendingCancellationWithoutRetrying() {
        UUID document = document("policy.txt");
        Held held = reserveWithoutDispatch(document);
        clock.instant = clock.instant.plusSeconds(46);
        view(held.run());
        cancel(held.run()).expectStatus().isOk().expectBody().jsonPath("$.cancellationPending").isEqualTo(true);
        jobs.markFailed(held.job(), "private diagnostic details", "EMPTY_TEXT", OffsetDateTime.now(clock)).block();
        JsonNode cancelled = view(held.run());
        assertThat(cancelled.path("status").asText()).isEqualTo("CANCELLED");
        assertThat(cancelled.path("actionResults").get(0).path("status").asText()).isEqualTo("FAILED");
        assertThat(cancelled.path("actionResults").get(0).path("errorCode").asText()).isEqualTo("EMPTY_TEXT");
        assertThat(cancelled.toString()).doesNotContain("private diagnostic details");
        assertThat(count("ingestion_jobs", document)).isEqualTo(1);
        verify(extraction, never()).extract(any());
    }

    @Test void knownFailedJobResumesDiagnosisButDoesNotRepeatTheConsumedApproval() {
        UUID document = document("policy.txt");
        Held held = reserveWithoutDispatch(document);
        clock.instant = clock.instant.plusSeconds(46);
        view(held.run());
        jobs.markFailed(held.job(), "private error", "UNKNOWN", OffsetDateTime.now(clock)).block();
        assertThat(view(held.run()).path("status").asText()).isEqualTo("QUEUED");
        JsonNode resumed = claim();
        assertThat(resumed.hasNonNull("pendingApprovalId")).isFalse();
        assertThat(execute(resumed, held.approval()).path("status").asText()).isEqualTo("FAILED");
        assertThat(count("ingestion_jobs", document)).isEqualTo(1);
        verify(extraction, never()).extract(any());
    }

    @Test void confirmedWriteFollowedByWorkerLossResumesOnlyDiagnosis() {
        UUID document = document("policy.txt");
        JsonNode run = start(document);
        String approval = propose(run, document, "CHUNK").path("data").path("approvalId").asText();
        decision(run, approval, "APPROVE", OWNER).expectStatus().isOk();
        JsonNode resumed = claim();
        assertThat(execute(resumed, approval).path("status").asText()).isEqualTo("SUCCEEDED");
        var before = chunks.findByDocumentId(document).block();
        clock.instant = clock.instant.plusSeconds(46);
        JsonNode recovered = claim();
        assertThat(recovered.hasNonNull("pendingApprovalId")).isFalse();
        assertThat(recovered.path("runId")).isEqualTo(run.path("runId"));
        assertThat(execute(recovered, approval).path("status").asText()).isEqualTo("SUCCEEDED");
        assertThat(chunks.findByDocumentId(document).block()).isEqualTo(before);
        assertThat(count("ingestion_jobs", document)).isEqualTo(1);
    }

    @Test void unconsumedApprovalSurvivesRecoveryWithOnlyOneFirstDispatch() {
        UUID document = document("policy.txt");
        JsonNode run = start(document);
        String approval = propose(run, document, "CHUNK").path("data").path("approvalId").asText();
        decision(run, approval, "APPROVE", OWNER).expectStatus().isOk();
        claim();
        clock.instant = clock.instant.plusSeconds(46);
        JsonNode recovered = claim();
        assertThat(recovered.path("pendingApprovalId").asText()).isEqualTo(approval);
        assertThat(count("ingestion_jobs", document)).isZero();
        assertThat(execute(recovered, approval).path("status").asText()).isEqualTo("SUCCEEDED");
        assertThat(count("ingestion_jobs", document)).isEqualTo(1);
    }

    @Test void concurrentCancelAndApproveCannotDispatchAnUnapprovedOrCancelledWrite() {
        UUID document = document("policy.txt");
        JsonNode run = start(document);
        UUID approval = UUID.fromString(propose(run, document, "CHUNK").path("data").path("approvalId").asText());
        var results = Flux.merge(runs.cancel(id(run), OWNER).materialize(),
                approvals.decide(id(run), approval, "APPROVE", OWNER).materialize()).collectList().block();
        assertThat(results.stream().anyMatch(signal -> signal.isOnNext())).isTrue();
        assertThat(view(run).path("status").asText()).isEqualTo("CANCELLED");
        assertThat(count("ingestion_jobs", document)).isZero();
        client.post().uri("/internal/agent-worker/claims").header("X-Worker-Token", TOKEN).exchange().expectStatus().isNoContent();
        verify(extraction, never()).extract(any());
    }

    // Simulate a crash just after the atomic execution/job reservation, before business dispatch.
    private Held reserveWithoutDispatch(UUID document) {
        JsonNode run = start(document);
        String approval = propose(run, document, "CHUNK").path("data").path("approvalId").asText();
        decision(run, approval, "APPROVE", OWNER).expectStatus().isOk();
        JsonNode resumed = claim();
        UUID execution = UUID.fromString(approvalRepository.execution(id(run), UUID.fromString(approval)).block().path("executionId").asText());
        UUID job = repository.locked(id(run), current -> approvalRepository.executionStatus(execution, "RUNNING", null)
                .then(jobs.createLinked(document, "tenant-a", IngestionJobType.CHUNK, OffsetDateTime.now(clock), execution, current.traceId()))
                .flatMap(saved -> repository.event(current.id(), "action_started", mapper.createObjectNode()
                        .put("executionId", execution.toString()).put("ingestionJobId", saved.id().toString())).thenReturn(saved.id()))).block();
        return new Held(resumed, approval, job);
    }

    private UUID id(JsonNode run) { return UUID.fromString(run.path("runId").asText()); }
    private WebTestClient.ResponseSpec cancel(JsonNode run) {
        return client.post().uri("/api/v1/agent/runs/" + run.path("runId").asText() + "/cancel")
                .header("X-Tenant-Id", "tenant-a").header("X-Actor-Id", "alice").exchange();
    }
    private record Held(JsonNode run, String approval, UUID job) { }

    private void worker(Path temporary) throws Exception {
        Path path = Path.of("workers/pi-worker/dist/src/main.js").toAbsolutePath();
        assertThat(path).exists();
        Path log = temporary.resolve(UUID.randomUUID() + ".log");
        var builder = new ProcessBuilder("node", path.toString(), "--once").redirectErrorStream(true).redirectOutput(log.toFile());
        builder.environment().put("NEXUS_AGENT_API_URL", "http://localhost:" + port);
        builder.environment().put("NEXUS_AGENT_WORKER_MODE", "scripted");
        builder.environment().put("NEXUS_AGENT_WORKER_TOKEN", TOKEN);
        Process process = builder.start();
        try {
            assertThat(process.waitFor(20, TimeUnit.SECONDS)).isTrue();
            assertThat(process.exitValue()).withFailMessage(Files.readString(log)).isZero();
        } finally { if (process.isAlive()) { process.destroyForcibly().waitFor(); } }
    }

    private UUID document(String name) {
        UUID id = UUID.randomUUID();
        documents.save(DocumentMetadata.stored(id, "tenant-a", "alice", DocumentVisibility.PRIVATE, name,
                name.endsWith("pdf") ? "application/pdf" : "text/plain", 42, "a".repeat(64), "test", "documents/" + id,
                OffsetDateTime.now(clock))).block();
        return id;
    }
    private long count(String table, UUID document) {
        return db.sql("SELECT count(*) AS n FROM " + table + " WHERE document_id=:id").bind("id", document)
                .map(row -> row.get("n", Long.class)).one().block();
    }
    private void job(UUID document, String status, String code) {
        db.sql("""
                INSERT INTO ingestion_jobs(id, document_id, tenant_id, job_type, status, error_code, error_message, created_at, updated_at)
                VALUES (:id, :document, 'tenant-a', 'CHUNK', :status, :code, 'raw error never model-visible', :now, :now)
                """).bind("id", UUID.randomUUID()).bind("document", document).bind("status", status).bind("code", code)
                .bind("now", OffsetDateTime.now(clock)).fetch().rowsUpdated().block();
    }
    private JsonNode start(UUID document) { runs.create(request(document, "Inspect processing"), OWNER, null, null).block(); return claim(); }
    private ObjectNode request(UUID document, String question) {
        return mapper.createObjectNode().put("question", question).set("documentIds", mapper.valueToTree(List.of(document)));
    }
    private JsonNode claim() {
        return client.post().uri("/internal/agent-worker/claims").header("X-Worker-Token", TOKEN).exchange().expectStatus().isOk()
                .expectBody(JsonNode.class).returnResult().getResponseBody();
    }
    private JsonNode view(JsonNode run) { return runs.get(UUID.fromString(run.path("runId").asText()), OWNER).block(); }
    private void inspect(JsonNode run, UUID document) {
        for (String name : List.of("inspect_document", "list_ingestion_jobs")) {
            var body = mapper.createObjectNode().put("schemaVersion", 1).put("invocationId", UUID.randomUUID().toString()).put("toolName", name);
            body.set("arguments", mapper.createObjectNode().put("documentId", document.toString()));
            assertThat(call(run, body).path("status").asText()).isEqualTo("SUCCEEDED");
        }
    }
    private ObjectNode proposalBody(UUID document, String action) {
        ObjectNode body = mapper.createObjectNode().put("schemaVersion", 1).put("invocationId", UUID.randomUUID().toString())
                .put("toolName", "propose_retry");
        return body.set("arguments", mapper.createObjectNode().put("documentId", document.toString()).put("action", action).put("reason", "Metadata indicates missing processing"));
    }
    private JsonNode propose(JsonNode run, UUID document, String action) { inspect(run, document); return call(run, proposalBody(document, action)); }
    private JsonNode call(JsonNode run, JsonNode body) {
        return internal(run, "/tools").bodyValue(body).exchange().expectStatus().isOk().expectBody(JsonNode.class).returnResult().getResponseBody();
    }
    private JsonNode execute(JsonNode run, String approval) {
        return internal(run, "/approved-retries/" + approval).exchange().expectStatus().isOk().expectBody(JsonNode.class).returnResult().getResponseBody();
    }
    private WebTestClient.RequestBodySpec internal(JsonNode run, String path) {
        return client.post().uri("/internal/agent-worker/runs/" + run.path("runId").asText() + path).header("X-Claim-Token", run.path("claimToken").asText());
    }
    private String publicApproval(JsonNode run, String approval) { return "/api/v1/agent/runs/" + run.path("runId").asText() + "/approvals/" + approval; }
    private WebTestClient.ResponseSpec decision(JsonNode run, String approval, String decision, RequestContext actor) {
        return client.post().uri(publicApproval(run, approval)).header("X-Tenant-Id", actor.tenantId()).header("X-Actor-Id", actor.actorId())
                .bodyValue(mapper.createObjectNode().put("decision", decision)).exchange();
    }
}
