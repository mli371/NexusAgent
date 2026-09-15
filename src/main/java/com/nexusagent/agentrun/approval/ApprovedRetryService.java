package com.nexusagent.agentrun.approval;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.UUID;

import com.fasterxml.jackson.databind.JsonNode;
import com.nexusagent.agentrun.application.AgentRunService;
import com.nexusagent.agentrun.application.RunException;
import com.nexusagent.agentrun.application.RunJson;
import com.nexusagent.agentrun.application.RunLifecycleService;
import com.nexusagent.agentrun.domain.AgentRun;
import com.nexusagent.agentrun.repository.AgentRunRepository;
import com.nexusagent.chunking.application.DocumentChunkingService;
import com.nexusagent.embeddings.application.ChildChunkEmbeddingService;
import com.nexusagent.enterprise.ingestion.IngestionFailure;
import com.nexusagent.enterprise.ingestion.IngestionJobRepository;
import com.nexusagent.enterprise.ingestion.IngestionJobType;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

@Service
public class ApprovedRetryService {
    private final AgentRunRepository repository;
    private final AgentRunService runs;
    private final ApprovalRepository approvals;
    private final RetryPolicy policy;
    private final IngestionJobRepository jobs;
    private final DocumentChunkingService chunks;
    private final ChildChunkEmbeddingService embeddings;
    private final DatabaseClient db;
    private final RunJson json;
    private final Clock clock;
    private final RunLifecycleService lifecycle;

    public ApprovedRetryService(AgentRunRepository repository, AgentRunService runs, ApprovalRepository approvals,
                                RetryPolicy policy, IngestionJobRepository jobs, DocumentChunkingService chunks,
                                ChildChunkEmbeddingService embeddings, DatabaseClient db, RunJson json, Clock clock,
                                RunLifecycleService lifecycle) {
        this.repository = repository; this.runs = runs; this.approvals = approvals; this.policy = policy;
        this.jobs = jobs; this.chunks = chunks; this.embeddings = embeddings; this.db = db; this.json = json; this.clock = clock;
        this.lifecycle = lifecycle;
    }

    public Mono<JsonNode> execute(UUID runId, String token, UUID approvalId) {
        return repository.locked(runId, run -> {
            runs.authorize(run, token, true);
            return repository.documents(runId).collectList().flatMap(ids -> runs.checkDocuments(ids, run.context()))
                    .then(approvals.find(runId, approvalId)).switchIfEmpty(Mono.error(RunException.missing()))
                    .flatMap(approval -> {
                        if (!approval.status().equals("APPROVED")) { return Mono.error(RunException.conflict("Action is not approved")); }
                        return approvals.execution(runId, approvalId).flatMap(execution -> {
                            if (!execution.path("status").asText().equals("PENDING")) {
                                return Mono.just(new Dispatch(run, approval, execution, null));
                            }
                            UUID executionId = UUID.fromString(execution.path("executionId").asText());
                            return policy.inspect(run, approval.documentId(), approval.action()).flatMap(state -> {
                                String code = state.denial() != null ? state.denial() : !state.needed() ? "ALREADY_COMPLETE"
                                        : !state.fingerprint().equals(approval.fingerprint()) ? "DOCUMENT_STATE_CHANGED" : null;
                                if (code != null) {
                                    String status = code.equals("ALREADY_COMPLETE") ? "SKIPPED" : "FAILED";
                                    return finishRecord(runId, approvalId, executionId, status, code)
                                            .map(result -> new Dispatch(run, approval, result, null));
                                }
                                return approvals.executionStatus(executionId, "RUNNING", null)
                                        .then(jobs.createLinked(approval.documentId(), run.tenantId(),
                                                approval.action().equals("CHUNK") ? IngestionJobType.CHUNK : IngestionJobType.EMBED,
                                                OffsetDateTime.now(clock), executionId, run.traceId()))
                                        .flatMap(job -> repository.event(runId, "action_started", json.object()
                                                .put("approvalId", approvalId.toString()).put("executionId", executionId.toString())
                                                .put("ingestionJobId", job.id().toString()))
                                                .then(approvals.execution(runId, approvalId))
                                                .map(result -> new Dispatch(run, approval, result, job.id())));
                            });
                        });
                    });
        }).flatMap(dispatch -> {
            if (dispatch.jobId() == null) { return Mono.just(dispatch.result()); }
            Approval approval = dispatch.approval();
            AgentRun run = dispatch.run();
            // The reservation, execution ID and exact job ID are committed before any ingestion I/O.
            // Never retry this publisher or put a timeout around a possibly committed business write.
            Mono<?> work = approval.action().equals("CHUNK")
                    ? chunks.executeApproved(approval.documentId(), run.context(), dispatch.jobId(), run.traceId())
                    : embeddings.executeApproved(approval.documentId(), run.context(), dispatch.jobId(), run.traceId());
            return work.thenReturn(new Outcome("SUCCEEDED", null))
                    .onErrorResume(error -> db.sql("SELECT status FROM ingestion_jobs WHERE id=:id")
                            .bind("id", dispatch.jobId()).map(row -> row.get("status", String.class)).one()
                            .map(status -> status.equals("FAILED") ? new Outcome("FAILED", IngestionFailure.code(error))
                                    : new Outcome("UNKNOWN", "ACTION_OUTCOME_UNKNOWN")))
                    .flatMap(outcome -> repository.locked(runId, current -> lifecycle.actionFinished(current, run.claimHash(),
                            approvalId, UUID.fromString(dispatch.result().path("executionId").asText()), outcome.status(), outcome.code())));
        });
    }

    private Mono<JsonNode> finishRecord(UUID run, UUID approval, UUID execution, String status, String code) {
        return approvals.executionStatus(execution, status, code)
                .then(repository.event(run, "action_completed", json.object().put("approvalId", approval.toString())
                        .put("executionId", execution.toString()).put("status", status).put("code", code)))
                .then(approvals.execution(run, approval));
    }

    private record Dispatch(AgentRun run, Approval approval, JsonNode result, UUID jobId) { }
    private record Outcome(String status, String code) { }
}
