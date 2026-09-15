package com.nexusagent.agentrun.application;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.Set;
import java.util.UUID;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.nexusagent.agentrun.approval.ApprovalRepository;
import com.nexusagent.agentrun.domain.AgentRun;
import com.nexusagent.agentrun.repository.AgentRunRepository;
import com.nexusagent.agentrun.tools.DocumentDiagnosticsService;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

/** Durable control transitions; no business mutation or model call is retried here. */
@Service
public class RunLifecycleService {
    private final AgentRunRepository runs;
    private final ApprovalRepository approvals;
    private final DocumentDiagnosticsService diagnostics;
    private final DatabaseClient db;
    private final AgentRunProperties properties;
    private final RunJson json;
    private final Clock clock;

    public RunLifecycleService(AgentRunRepository runs, ApprovalRepository approvals, DocumentDiagnosticsService diagnostics,
                               DatabaseClient db, AgentRunProperties properties, RunJson json, Clock clock) {
        this.runs = runs;
        this.approvals = approvals;
        this.diagnostics = diagnostics;
        this.db = db;
        this.properties = properties;
        this.json = json;
        this.clock = clock;
    }

    public Mono<Void> maintain() {
        return approvals.expire().thenMany(db.sql("""
                SELECT id FROM agent_runs WHERE status='RECOVERY_REQUIRED'
                   OR (status='RUNNING' AND (lease_expires_at<=:now OR deadline_at<=:now))
                ORDER BY updated_at, id LIMIT 100
                """).bind("now", now()).map(row -> row.get("id", UUID.class)).all())
                .concatMap(id -> runs.locked(id, run -> {
                    if (run.status().equals("RECOVERY_REQUIRED")) { return reconcile(run); }
                    if (!run.running() || activeLease(run)) { return Mono.empty(); }
                    return hasUncertainAction(id).flatMap(uncertain -> {
                        if (uncertain) { return recoveryRequired(run); }
                        if (run.cancellationRequested()) { return cancelFinal(run); }
                        if (!run.deadlineAt().isAfter(now())) { return runs.finish(run, null, "RUN_DEADLINE"); }
                        return recover(run, "WORKER_LOST");
                    });
                })).then();
    }

    public boolean activeLease(AgentRun run) {
        return run.running() && run.leaseExpiresAt().isAfter(now()) && run.deadlineAt().isAfter(now());
    }

    // Called under the run row lock, after caller identity and current access have been verified.
    public Mono<Void> requestCancel(AgentRun run) {
        if (run.terminal() || run.cancellationRequested()) { return Mono.empty(); }
        return db.sql("UPDATE agent_runs SET cancel_requested_at=:now WHERE id=:id")
                .bind("id", run.id()).bind("now", now()).fetch().rowsUpdated()
                .then(runs.event(run.id(), "cancel_requested", json.object()))
                .then(hasUncertainAction(run.id())).flatMap(uncertain -> uncertain
                        ? Mono.empty() : cancelFinal(run));
    }

    public Mono<Void> fail(AgentRun run, String code) {
        return hasUncertainAction(run.id()).flatMap(uncertain -> uncertain ? recoveryRequired(run)
                : run.cancellationRequested() ? cancelFinal(run) : runs.finish(run, null, code));
    }

    public Mono<Void> recoveryRequired(AgentRun run) {
        if (run.terminal() || run.status().equals("RECOVERY_REQUIRED")) { return Mono.empty(); }
        return db.sql("""
                UPDATE agent_action_executions SET status='UNKNOWN', error_code='ACTION_OUTCOME_UNKNOWN', finished_at=NULL
                WHERE run_id=:run AND status='RUNNING'
                """).bind("run", run.id()).fetch().rowsUpdated()
                .then(runs.closeTools(run.id()))
                .then(db.sql("UPDATE agent_runs SET status='RECOVERY_REQUIRED', error_code='ACTION_OUTCOME_UNKNOWN' WHERE id=:id")
                        .bind("id", run.id()).fetch().rowsUpdated())
                .then(runs.event(run.id(), "recovery_required", json.object().put("code", "ACTION_OUTCOME_UNKNOWN")));
    }

    /** Only the Java operation that reserved this execution may deliver this late outcome. */
    public Mono<JsonNode> actionFinished(AgentRun current, String originalClaim, UUID approvalId,
                                         UUID executionId, String status, String code) {
        return approvals.execution(current.id(), approvalId).flatMap(saved -> {
            if (!Set.of("RUNNING", "UNKNOWN").contains(saved.path("status").asText())) {
                return Mono.just(withRunStatus(saved, current.status()));
            }
            Mono<Void> transition;
            if (status.equals("UNKNOWN")) { transition = recoveryRequired(current); }
            else if (current.cancellationRequested()) { transition = cancelFinal(current); }
            else if (activeLease(current) && originalClaim.equals(current.claimHash())) { transition = Mono.empty(); }
            else { transition = recoveryRequired(current); }
            return approvals.executionStatus(executionId, status, code)
                    .then(runs.event(current.id(), "action_completed", json.object().put("approvalId", approvalId.toString())
                            .put("executionId", executionId.toString()).put("status", status).put("code", code)))
                    .then(transition).then(runs.find(current.id()))
                    .flatMap(updated -> approvals.execution(current.id(), approvalId)
                            .map(result -> withRunStatus(result, updated.status())));
        });
    }

    private Mono<Void> recover(AgentRun run, String reason) {
        if (run.recoveryCount() >= properties.getMaxRecoveries()) { return runs.finish(run, null, "RECOVERY_EXHAUSTED"); }
        return runs.documents(run.id()).collectList().flatMap(ids -> {
            if (run.toolCount() + 2 * ids.size() > properties.getMaxTools()
                    || (run.executionMode().equals("pi") && run.roundCount() >= properties.getMaxRounds())
                    || run.eventSequence() + 5 + 2 * ids.size() >= properties.getMaxEvents()) {
                return runs.finish(run, null, "BUDGET_EXCEEDED");
            }
            return reactor.core.publisher.Flux.fromIterable(ids).concatMap(id -> diagnostics.accessible(id, run.context())).then()
                    .then(runs.closeTools(run.id()))
                    .then(db.sql("""
                            UPDATE agent_runs SET status='QUEUED', claim_hash=NULL, lease_expires_at=NULL,
                                deadline_at=NULL, error_code=NULL, recovery_count=recovery_count+1 WHERE id=:id
                            """).bind("id", run.id()).fetch().rowsUpdated())
                    .then(runs.event(run.id(), "recovery_queued", json.object().put("reason", reason)
                            .put("recoveryCount", run.recoveryCount() + 1)))
                    .onErrorResume(RunException.class, error -> error.status() == 404
                            ? runs.finish(run, null, "ACCESS_REVOKED") : Mono.error(error));
        });
    }

    private Mono<Void> cancelFinal(AgentRun run) {
        return runs.closeTools(run.id())
                .then(db.sql("UPDATE agent_approvals SET status='CANCELLED' WHERE run_id=:id AND status='PENDING'")
                        .bind("id", run.id()).fetch().rowsUpdated())
                .then(db.sql("""
                        UPDATE agent_action_executions SET status='SKIPPED', error_code='RUN_CANCELLED', finished_at=:now
                        WHERE run_id=:id AND status='PENDING'
                        """).bind("id", run.id()).bind("now", now()).fetch().rowsUpdated())
                .then(approvals.cancel(run.id(), "CANCELLED_BY_USER"));
    }

    private Mono<Boolean> hasUncertainAction(UUID run) {
        return approvals.executions(run).any(value -> Set.of("RUNNING", "UNKNOWN").contains(value.path("status").asText()));
    }

    private Mono<Void> reconcile(AgentRun run) {
        // Only the exact linked job is evidence. A similar/latest job or elapsed timeout is not proof.
        return db.sql("""
                SELECT e.id, e.approval_id, a.document_id, a.action, j.status AS job_status, j.error_code
                FROM agent_action_executions e JOIN agent_approvals a ON a.id=e.approval_id
                LEFT JOIN ingestion_jobs j ON j.agent_execution_id=e.id
                WHERE e.run_id=:run AND e.status IN ('UNKNOWN', 'RUNNING') ORDER BY e.created_at, e.id
                """).bind("run", run.id()).map(row -> new Evidence(row.get("id", UUID.class), row.get("approval_id", UUID.class),
                        row.get("document_id", UUID.class), row.get("action", String.class),
                        row.get("job_status", String.class), row.get("error_code", String.class))).all()
                .concatMap(evidence -> reconcileAction(run, evidence)).then(hasUncertainAction(run.id()))
                .flatMap(uncertain -> uncertain ? touchForFairPolling(run.id())
                        : run.cancellationRequested() ? cancelFinal(run) : recover(run, "ACTION_RECONCILED"));
    }

    private Mono<Void> reconcileAction(AgentRun run, Evidence evidence) {
        if (!Set.of("SUCCEEDED", "FAILED").contains(evidence.jobStatus() == null ? "" : evidence.jobStatus())) {
            return Mono.empty();
        }
        return diagnostics.inspect(evidence.document(), run.context()).flatMap(state -> {
            boolean confirmed = evidence.jobStatus().equals("FAILED") || (evidence.action().equals("CHUNK")
                    ? state.path("parentChunkCount").asLong() > 0 && state.path("childChunkCount").asLong() > 0
                    : state.path("condition").asText().equals("HEALTHY"));
            if (!confirmed) { return Mono.empty(); }
            String code = evidence.jobStatus().equals("SUCCEEDED") ? null : safeCode(evidence.code());
            return approvals.executionStatus(evidence.execution(), evidence.jobStatus(), code)
                    .then(runs.event(run.id(), "action_reconciled", json.object()
                            .put("executionId", evidence.execution().toString()).put("approvalId", evidence.approval().toString())
                            .put("status", evidence.jobStatus()).put("currentCondition", state.path("condition").asText())));
        }).onErrorResume(RunException.class, error -> error.status() == 404 ? Mono.empty() : Mono.error(error));
    }

    private Mono<Void> touchForFairPolling(UUID id) {
        // Rotate unresolved records through the bounded maintenance batch without growing event history.
        return db.sql("UPDATE agent_runs SET updated_at=:now WHERE id=:id").bind("id", id).bind("now", now())
                .fetch().rowsUpdated().then();
    }

    private String safeCode(String code) {
        return code != null && Set.of("UNSUPPORTED_TYPE", "EMPTY_TEXT", "CHUNKING_REQUIRED", "TRANSIENT_DEPENDENCY",
                "EMBEDDING_INCOMPLETE", "EMBEDDING_MODEL_MISMATCH").contains(code) ? code : "UNKNOWN";
    }

    private JsonNode withRunStatus(JsonNode result, String status) {
        ObjectNode response = result.deepCopy();
        if (!status.equals("RUNNING")) { response.put("runStatus", status); }
        return response;
    }

    private OffsetDateTime now() { return OffsetDateTime.now(clock); }
    private record Evidence(UUID execution, UUID approval, UUID document, String action, String jobStatus, String code) { }
}
