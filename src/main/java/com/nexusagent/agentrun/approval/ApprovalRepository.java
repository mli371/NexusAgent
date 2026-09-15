package com.nexusagent.agentrun.approval;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.UUID;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.nexusagent.agentrun.application.AgentRunProperties;
import com.nexusagent.agentrun.application.RunJson;
import com.nexusagent.agentrun.domain.AgentRun;
import com.nexusagent.agentrun.repository.AgentRunRepository;
import io.r2dbc.spi.Readable;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/** Transition methods are called inside AgentRunRepository.locked. */
@Repository
public class ApprovalRepository {
    private static final String SELECT = "SELECT *, arguments::text AS args FROM agent_approvals ";
    private final DatabaseClient db;
    private final AgentRunRepository runs;
    private final AgentRunProperties properties;
    private final RunJson json;
    private final Clock clock;

    public ApprovalRepository(DatabaseClient db, AgentRunRepository runs, AgentRunProperties properties, RunJson json, Clock clock) {
        this.db = db; this.runs = runs; this.properties = properties; this.json = json; this.clock = clock;
    }

    public Mono<Approval> find(UUID run, UUID id) {
        return db.sql(SELECT + "WHERE run_id=:run AND id=:id").bind("run", run).bind("id", id).map(this::map).one();
    }

    public Flux<Approval> list(UUID run) {
        return db.sql(SELECT + "WHERE run_id=:run ORDER BY created_at, id").bind("run", run).map(this::map).all();
    }

    public Mono<Approval> create(AgentRun run, UUID document, String action, JsonNode args, String fingerprint, String reason) {
        UUID id = UUID.randomUUID();
        return db.sql("""
                INSERT INTO agent_approvals(id, run_id, document_id, action, arguments, arguments_hash,
                    state_fingerprint, reason, status, requested_by, expires_at, created_at)
                VALUES (:id, :run, :document, :action, CAST(:args AS jsonb), :hash, :fingerprint, :reason,
                    'PENDING', :actor, :expiry, :now)
                """).bind("id", id).bind("run", run.id()).bind("document", document).bind("action", action)
                .bind("args", args.toString()).bind("hash", RunJson.hash(args.toString())).bind("fingerprint", fingerprint)
                .bind("reason", reason).bind("actor", run.actorId()).bind("expiry", now().plus(properties.getApprovalTtl()))
                .bind("now", now()).fetch().rowsUpdated().then(find(run.id(), id));
    }

    public Mono<Void> pause(Approval approval) {
        return db.sql("UPDATE agent_runs SET status='WAITING_APPROVAL' WHERE id=:id AND status='RUNNING'")
                .bind("id", approval.runId()).fetch().rowsUpdated()
                .then(runs.event(approval.runId(), "approval_requested", json.object().put("approvalId", approval.id().toString())
                        .put("documentId", approval.documentId().toString()).put("action", approval.action())));
    }

    public Mono<Void> decide(Approval approval, String decision, String actor) {
        return db.sql("""
                UPDATE agent_approvals SET status=:status, decided_by=:actor, decided_at=:now
                WHERE id=:id AND status='PENDING'
                """).bind("id", approval.id()).bind("status", decision).bind("actor", actor).bind("now", now())
                .fetch().rowsUpdated().then(Mono.defer(() -> {
                    if (!decision.equals("APPROVED")) { return cancel(approval.runId(), "APPROVAL_REJECTED"); }
                    return db.sql("""
                            INSERT INTO agent_action_executions(id, approval_id, run_id, status, created_at)
                            VALUES (:id, :approval, :run, 'PENDING', :now)
                            """).bind("id", UUID.randomUUID()).bind("approval", approval.id()).bind("run", approval.runId())
                            .bind("now", now()).fetch().rowsUpdated()
                            .then(db.sql("UPDATE agent_runs SET status='QUEUED', claim_hash=NULL WHERE id=:id")
                                    .bind("id", approval.runId()).fetch().rowsUpdated())
                            .then(runs.event(approval.runId(), "approval_approved", json.object().put("approvalId", approval.id().toString())));
                }));
    }

    public Mono<Void> expire() {
        return db.sql("SELECT run_id FROM agent_approvals WHERE status='PENDING' AND expires_at<=:now")
                .bind("now", now()).map(row -> row.get("run_id", UUID.class)).all()
                .concatMap(id -> runs.locked(id, run -> {
                    if (!run.status().equals("WAITING_APPROVAL")) { return Mono.empty(); }
                    return db.sql("UPDATE agent_approvals SET status='EXPIRED' WHERE run_id=:run AND status='PENDING' AND expires_at<=:now")
                            .bind("run", id).bind("now", now()).fetch().rowsUpdated()
                            .flatMap(changed -> changed == 0 ? Mono.empty() : cancel(id, "APPROVAL_EXPIRED"));
                })).then();
    }

    public Mono<Void> cancel(UUID run, String reason) {
        return db.sql("UPDATE agent_runs SET status='CANCELLED', error_code=:code WHERE id=:id")
                .bind("id", run).bind("code", reason).fetch().rowsUpdated()
                .then(runs.event(run, "cancelled", json.object().put("code", reason)));
    }

    public Flux<JsonNode> executions(UUID run) {
        return db.sql("""
                SELECT e.*, a.document_id, a.action, j.id AS job_id FROM agent_action_executions e
                JOIN agent_approvals a ON a.id=e.approval_id LEFT JOIN ingestion_jobs j ON j.agent_execution_id=e.id
                WHERE e.run_id=:run ORDER BY e.created_at, e.id
                """).bind("run", run).map(row -> {
                    ObjectNode value = json.object().put("executionId", row.get("id", UUID.class).toString())
                            .put("approvalId", row.get("approval_id", UUID.class).toString())
                            .put("documentId", row.get("document_id", UUID.class).toString()).put("action", row.get("action", String.class))
                            .put("status", row.get("status", String.class)).put("errorCode", row.get("error_code", String.class));
                    UUID job = row.get("job_id", UUID.class);
                    value.put("ingestionJobId", job == null ? null : job.toString());
                    return (JsonNode) value;
                }).all();
    }

    public Mono<JsonNode> execution(UUID run, UUID approval) {
        return executions(run).filter(value -> approval.toString().equals(value.path("approvalId").asText())).singleOrEmpty();
    }

    public Mono<Void> executionStatus(UUID id, String status, String code) {
        var update = db.sql("""
                UPDATE agent_action_executions SET status=:status, error_code=:code, finished_at=:finished
                WHERE id=:id
                """).bind("id", id).bind("status", status);
        update = code == null ? update.bindNull("code", String.class) : update.bind("code", code);
        update = status.equals("RUNNING") || status.equals("UNKNOWN")
                ? update.bindNull("finished", OffsetDateTime.class) : update.bind("finished", now());
        return update.fetch().rowsUpdated().then();
    }

    public Mono<Boolean> hasUnfinished(UUID run) {
        return executions(run).any(value -> java.util.Set.of("PENDING", "RUNNING").contains(value.path("status").asText()));
    }

    public Mono<JsonNode> snapshot(UUID run) {
        return db.sql("""
                SELECT result::text AS result_text FROM (
                    SELECT DISTINCT ON (arguments->>'documentId', tool_name) * FROM agent_tool_calls
                    WHERE run_id=:run AND tool_name IN ('inspect_document', 'list_ingestion_jobs') AND status='SUCCEEDED'
                    ORDER BY arguments->>'documentId', tool_name, attempt DESC, created_at DESC, id DESC
                ) latest ORDER BY arguments->>'documentId', tool_name
                """).bind("run", run).map(row -> json.parse(row.get("result_text", String.class))).all().collectList()
                .flatMap(observations -> list(run).map(this::view).collectList().flatMap(decisions -> executions(run).collectList()
                        .map(results -> {
                            var snapshot = json.object().put("schemaVersion", 1);
                            snapshot.set("observations", json.tree(observations));
                            snapshot.set("approvals", json.tree(decisions));
                            snapshot.set("actionResults", json.tree(results));
                            RunJson.limit(snapshot, 32768);
                            return (JsonNode) snapshot;
                        })));
    }

    public JsonNode view(Approval approval) {
        ObjectNode value = json.object().put("approvalId", approval.id().toString()).put("documentId", approval.documentId().toString())
                .put("action", approval.action()).put("reason", approval.reason()).put("status", approval.status())
                .put("stateFingerprint", approval.fingerprint()).put("expiresAt", approval.expiresAt().toString())
                .put("requestedBy", approval.requestedBy()).put("decidedBy", approval.decidedBy());
        value.set("arguments", approval.arguments());
        return value;
    }

    private Approval map(Readable row) {
        return new Approval(row.get("id", UUID.class), row.get("run_id", UUID.class), row.get("document_id", UUID.class),
                row.get("action", String.class), json.parse(row.get("args", String.class)), row.get("state_fingerprint", String.class),
                row.get("reason", String.class), row.get("status", String.class), row.get("requested_by", String.class),
                row.get("decided_by", String.class), row.get("expires_at", OffsetDateTime.class), row.get("decided_at", OffsetDateTime.class));
    }

    private OffsetDateTime now() { return OffsetDateTime.now(clock); }
}
