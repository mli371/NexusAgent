package com.nexusagent.agentrun.repository;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import java.util.function.Function;

import com.fasterxml.jackson.databind.JsonNode;
import com.nexusagent.agentrun.application.AgentRunProperties;
import com.nexusagent.agentrun.application.RunException;
import com.nexusagent.agentrun.application.RunJson;
import com.nexusagent.agentrun.domain.AgentRun;
import com.nexusagent.agentrun.domain.ToolCall;
import com.nexusagent.common.context.RequestContext;
import io.r2dbc.spi.Readable;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.ReactiveTransactionManager;
import org.springframework.transaction.reactive.TransactionalOperator;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@Repository
public class AgentRunRepository {
    private static final String RUN_SELECT = "SELECT *, report::text AS report_text FROM agent_runs ";
    private static final String TOOL_SELECT = "SELECT *, arguments::text AS args_text, result::text AS result_text FROM agent_tool_calls ";
    private final DatabaseClient db;
    private final TransactionalOperator tx;
    private final RunJson json;
    private final Clock clock;
    private final AgentRunProperties properties;

    public AgentRunRepository(DatabaseClient db, ReactiveTransactionManager manager, RunJson json,
                              Clock clock, AgentRunProperties properties) {
        this.db = db;
        this.tx = TransactionalOperator.create(manager);
        this.json = json;
        this.clock = clock;
        this.properties = properties;
    }

    public Mono<AgentRun> create(RequestContext context, String question, List<UUID> documents,
                                 String key, String hash, String traceId) {
        return Mono.defer(() -> {
            UUID id = UUID.randomUUID();
            DatabaseClient.GenericExecuteSpec insert = db.sql("""
                    INSERT INTO agent_runs(id, tenant_id, actor_id, trace_id, question, execution_mode,
                        provider, model, status, idempotency_key, request_hash, created_at, updated_at)
                    VALUES (:id, :tenant, :actor, :trace, :question, :mode, :provider, :model,
                        'QUEUED', :key, :hash, :now, :now)
                    ON CONFLICT (tenant_id, actor_id, idempotency_key) DO NOTHING RETURNING id
                    """)
                    .bind("id", id).bind("tenant", context.tenantId()).bind("actor", context.actorId())
                    .bind("trace", traceId).bind("question", question).bind("mode", properties.getWorkerMode())
                    .bind("provider", properties.getProvider()).bind("model", properties.getModel())
                    .bind("hash", hash).bind("now", now());
            insert = key == null ? insert.bindNull("key", String.class) : insert.bind("key", key);
            return insert.map(row -> row.get("id", UUID.class)).one()
                    .flatMap(created -> Flux.fromIterable(documents).concatMap(document -> db.sql("""
                            INSERT INTO agent_run_documents(run_id, document_id) VALUES (:run, :document)
                            """).bind("run", created).bind("document", document).fetch().rowsUpdated())
                            .then(event(id, "queued", json.object()))
                            .then(find(id)))
                    .switchIfEmpty(Mono.defer(() -> db.sql(RUN_SELECT + "WHERE tenant_id=:tenant AND actor_id=:actor AND idempotency_key=:key")
                            .bind("tenant", context.tenantId()).bind("actor", context.actorId()).bind("key", key)
                            .map(this::mapRun).one()
                            .flatMap(existing -> existing.requestHash().equals(hash) ? Mono.just(existing)
                                    : Mono.error(RunException.conflict("Idempotency key already has a different request")))));
        }).as(tx::transactional);
    }

    public Mono<AgentRun> find(UUID id) {
        return db.sql(RUN_SELECT + "WHERE id=:id").bind("id", id).map(this::mapRun).one();
    }

    public Flux<UUID> documents(UUID id) {
        return db.sql("SELECT document_id FROM agent_run_documents WHERE run_id=:id ORDER BY document_id")
                .bind("id", id).map(row -> row.get("document_id", UUID.class)).all();
    }

    public <T> Mono<T> locked(UUID id, Function<AgentRun, Mono<T>> work) {
        return db.sql(RUN_SELECT + "WHERE id=:id FOR UPDATE").bind("id", id).map(this::mapRun).one()
                .switchIfEmpty(Mono.error(RunException.missing())).flatMap(work).as(tx::transactional);
    }

    public Mono<AgentRun> claim(String claimHash) {
        return Mono.defer(() -> db.sql(RUN_SELECT + """
                        WHERE status='QUEUED' AND cancel_requested_at IS NULL AND execution_mode=:mode AND provider=:provider AND model=:model
                        AND NOT EXISTS (SELECT 1 FROM agent_runs WHERE status='RUNNING')
                        ORDER BY created_at, id LIMIT 1 FOR UPDATE SKIP LOCKED
                        """).bind("mode", properties.getWorkerMode()).bind("provider", properties.getProvider())
                .bind("model", properties.getModel()).map(this::mapRun).one()
                .flatMap(run -> db.sql("""
                        UPDATE agent_runs SET status='RUNNING', claim_hash=:hash, attempt=attempt+1,
                            lease_expires_at=:lease, deadline_at=:deadline WHERE id=:id
                        """).bind("id", run.id()).bind("hash", claimHash)
                        .bind("lease", now().plus(properties.getLease()))
                        .bind("deadline", now().plus(properties.getActiveTimeout()))
                        .fetch().rowsUpdated().then(event(run.id(), "started", json.object()))
                        .then(find(run.id()))))
                .as(tx::transactional)
                .onErrorResume(DuplicateKeyException.class, error -> Mono.empty());
    }

    public Mono<Void> heartbeat(AgentRun run) {
        OffsetDateTime lease = now().plus(properties.getLease());
        if (lease.isAfter(run.deadlineAt())) { lease = run.deadlineAt(); }
        return db.sql("UPDATE agent_runs SET lease_expires_at=:lease WHERE id=:id")
                .bind("id", run.id()).bind("lease", lease).fetch().rowsUpdated().then();
    }

    public Mono<Void> finish(AgentRun run, JsonNode report, String error) {
        DatabaseClient.GenericExecuteSpec update = db.sql("""
                UPDATE agent_runs SET status=:status, report=CAST(:report AS jsonb), error_code=:error WHERE id=:id
                """).bind("id", run.id()).bind("status", error == null ? "SUCCEEDED" : "FAILED");
        update = report == null ? update.bindNull("report", String.class) : update.bind("report", report.toString());
        update = error == null ? update.bindNull("error", String.class) : update.bind("error", error);
        return (error == null ? Mono.<Void>empty() : closeTools(run.id())).then(update.fetch().rowsUpdated())
                .then(event(run.id(), error == null ? "completed" : "failed",
                        json.object().put("code", error == null ? "COMPLETED" : error)));
    }

    public Mono<Void> closeTools(UUID id) {
        return tools(id)
                .filter(call -> "RUNNING".equals(call.status()))
                .concatMap(call -> finishTool(call, json.object().put("schemaVersion", 1)
                        .put("invocationId", call.invocationId().toString()).put("observationId", call.id().toString())
                        .put("status", "FAILED").put("observedAt", now().toString())
                        .set("error", json.object().put("code", "RUN_TERMINATED")
                                .put("message", "Run ended before the diagnostic result was saved").put("retryable", false))))
                .then();
    }

    public Mono<Void> event(UUID runId, String type, JsonNode payload) {
        RunJson.limit(payload, 2048);
        // All callers hold the run row lock, or have just inserted the row in this transaction.
        return db.sql("""
                UPDATE agent_runs SET event_sequence=event_sequence+1, version=version+1, updated_at=:now
                WHERE id=:id RETURNING event_sequence
                """).bind("id", runId).bind("now", now()).map(row -> row.get("event_sequence", Long.class)).one()
                .flatMap(sequence -> db.sql("""
                        INSERT INTO agent_run_events(run_id, sequence, event_type, payload, created_at)
                        VALUES (:id, :sequence, :type, CAST(:payload AS jsonb), :now)
                        """).bind("id", runId).bind("sequence", sequence).bind("type", type)
                        .bind("payload", payload.toString()).bind("now", now()).fetch().rowsUpdated()).then();
    }

    public Flux<JsonNode> events(UUID runId, long after) {
        return db.sql("""
                SELECT sequence, event_type, payload::text AS payload_text, created_at
                FROM agent_run_events WHERE run_id=:id AND sequence>:after ORDER BY sequence LIMIT 101
                """).bind("id", runId).bind("after", after).map(row -> (JsonNode) json.object()
                        .put("sequence", row.get("sequence", Long.class))
                        .put("eventType", row.get("event_type", String.class))
                        .put("createdAt", row.get("created_at", OffsetDateTime.class).toString())
                        .set("payload", json.parse(row.get("payload_text", String.class)))).all();
    }

    public Mono<ToolCall> tool(UUID runId, UUID invocationId) {
        return db.sql(TOOL_SELECT + "WHERE run_id=:run AND invocation_id=:invocation")
                .bind("run", runId).bind("invocation", invocationId).map(this::mapTool).one();
    }

    public Flux<ToolCall> tools(UUID runId) {
        return db.sql(TOOL_SELECT + "WHERE run_id=:run ORDER BY created_at, id")
                .bind("run", runId).map(this::mapTool).all();
    }

    public Flux<ToolCall> currentTools(AgentRun run) {
        return db.sql(TOOL_SELECT + "WHERE run_id=:run AND attempt=:attempt ORDER BY created_at, id")
                .bind("run", run.id()).bind("attempt", run.attempt()).map(this::mapTool).all();
    }

    public Mono<Void> reserveRound(AgentRun run, UUID reservation) {
        return db.sql("SELECT count(*) AS n FROM agent_model_rounds WHERE run_id=:run AND reservation_id=:id")
                .bind("run", run.id()).bind("id", reservation).map(row -> row.get("n", Long.class)).one()
                .flatMap(count -> {
                    if (count > 0) { return Mono.empty(); }
                    if (run.roundCount() >= properties.getMaxRounds()) {
                        return Mono.error(new RunException(409, "BUDGET_EXCEEDED", "Model round budget exhausted"));
                    }
                    return db.sql("INSERT INTO agent_model_rounds(run_id, reservation_id) VALUES (:run, :id)")
                            .bind("run", run.id()).bind("id", reservation).fetch().rowsUpdated()
                            .then(db.sql("UPDATE agent_runs SET round_count=round_count+1 WHERE id=:id")
                                    .bind("id", run.id()).fetch().rowsUpdated()).then();
                });
    }

    public Mono<Void> reserveReadRetry(UUID runId, UUID retryOf) {
        return db.sql("SELECT count(*) AS n FROM agent_tool_calls WHERE run_id=:run AND (retry_of=:retry OR (invocation_id=:retry AND retry_of IS NOT NULL))")
                .bind("run", runId).bind("retry", retryOf).map(row -> row.get("n", Long.class)).one()
                .flatMap(count -> count == 0 ? Mono.empty() : Mono.error(RunException.conflict("Read retry already used")));
    }

    public Mono<ToolCall> startTool(AgentRun run, UUID invocation, String name, JsonNode arguments, UUID retryOf) {
        UUID id = UUID.randomUUID();
        DatabaseClient.GenericExecuteSpec insert = db.sql("""
                INSERT INTO agent_tool_calls(id, run_id, attempt, invocation_id, tool_name, arguments,
                    arguments_hash, retry_of, status, created_at)
                VALUES (:id, :run, :attempt, :invocation, :name, CAST(:args AS jsonb), :hash, :retry,
                    'RUNNING', :now)
                """).bind("id", id).bind("run", run.id()).bind("attempt", run.attempt())
                .bind("invocation", invocation).bind("name", name).bind("args", arguments.toString())
                .bind("hash", RunJson.hash(arguments.toString())).bind("now", now());
        insert = retryOf == null ? insert.bindNull("retry", UUID.class) : insert.bind("retry", retryOf);
        return insert.fetch().rowsUpdated()
                .then(db.sql("UPDATE agent_runs SET tool_count=tool_count+1 WHERE id=:id")
                        .bind("id", run.id()).fetch().rowsUpdated())
                .then(event(run.id(), "tool_started", json.object().put("tool", name).put("observationId", id.toString())))
                .then(tool(run.id(), invocation));
    }

    public Mono<JsonNode> finishTool(ToolCall call, JsonNode result) {
        return db.sql("""
                UPDATE agent_tool_calls SET status=:status, result=CAST(:result AS jsonb), finished_at=:now
                WHERE id=:id AND status='RUNNING'
                """).bind("id", call.id()).bind("status", result.get("status").asText())
                .bind("result", result.toString()).bind("now", now()).fetch().rowsUpdated()
                .then(event(call.runId(), "tool_completed", json.object().put("tool", call.toolName())
                        .put("observationId", call.id().toString()).put("status", result.get("status").asText())))
                .thenReturn(result);
    }

    private OffsetDateTime now() { return OffsetDateTime.now(clock); }

    private AgentRun mapRun(Readable row) {
        return new AgentRun(row.get("id", UUID.class), row.get("tenant_id", String.class),
                row.get("actor_id", String.class), row.get("trace_id", String.class), row.get("question", String.class),
                row.get("execution_mode", String.class), row.get("provider", String.class), row.get("model", String.class),
                row.get("status", String.class), row.get("request_hash", String.class), row.get("claim_hash", String.class),
                row.get("attempt", Integer.class), row.get("lease_expires_at", OffsetDateTime.class),
                row.get("deadline_at", OffsetDateTime.class), row.get("tool_count", Integer.class),
                row.get("round_count", Integer.class),
                row.get("event_sequence", Long.class), json.parse(row.get("report_text", String.class)),
                row.get("error_code", String.class), row.get("created_at", OffsetDateTime.class),
                row.get("updated_at", OffsetDateTime.class), row.get("recovery_count", Integer.class),
                row.get("cancel_requested_at", OffsetDateTime.class));
    }

    private ToolCall mapTool(Readable row) {
        return new ToolCall(row.get("id", UUID.class), row.get("run_id", UUID.class),
                row.get("invocation_id", UUID.class), row.get("tool_name", String.class),
                row.get("arguments_hash", String.class), json.parse(row.get("args_text", String.class)),
                row.get("status", String.class), json.parse(row.get("result_text", String.class)));
    }
}
