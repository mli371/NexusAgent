package com.nexusagent.agentrun.application;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.nexusagent.agentrun.domain.AgentRun;
import com.nexusagent.agentrun.approval.ApprovalRepository;
import com.nexusagent.agentrun.repository.AgentRunRepository;
import com.nexusagent.agentrun.tools.DocumentDiagnosticsService;
import com.nexusagent.common.context.RequestContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@Service
public class AgentRunService {
    private static final Logger log = LoggerFactory.getLogger(AgentRunService.class);
    private final AgentRunRepository repository;
    private final DocumentDiagnosticsService diagnostics;
    private final AgentRunProperties properties;
    private final AgentReportValidator validator;
    private final RunJson json;
    private final ApprovalRepository approvals;
    private final RunLifecycleService lifecycle;
    private final SecureRandom random = new SecureRandom();

    public AgentRunService(AgentRunRepository repository, DocumentDiagnosticsService diagnostics,
                           AgentRunProperties properties, AgentReportValidator validator, RunJson json,
                           ApprovalRepository approvals, RunLifecycleService lifecycle) {
        this.repository = repository;
        this.diagnostics = diagnostics;
        this.properties = properties;
        this.validator = validator;
        this.json = json;
        this.approvals = approvals;
        this.lifecycle = lifecycle;
    }

    public Mono<JsonNode> create(JsonNode body, RequestContext context, String key, String trace) {
        return Mono.defer(() -> {
            enabled();
            RunJson.limit(body, 8192);
            RunJson.fields(body, "question", "documentIds");
            String question = RunJson.text(body, "question", 2000);
            JsonNode ids = body.get("documentIds");
            if (ids == null || !ids.isArray() || ids.isEmpty() || ids.size() > 10) {
                throw RunException.invalid("documentIds must contain 1 to 10 UUIDs");
            }
            List<UUID> documents = new ArrayList<>();
            for (JsonNode value : ids) { documents.add(RunJson.uuid(json.object().set("id", value), "id")); }
            documents = documents.stream().distinct().sorted().toList();
            if (key != null && !key.matches("[A-Za-z0-9._:-]{1,120}")) {
                throw RunException.invalid("Invalid Idempotency-Key");
            }
            if (trace != null && !trace.matches("[A-Za-z0-9._:-]{1,120}")) {
                throw RunException.invalid("Invalid X-Trace-Id");
            }
            String traceId = trace == null ? UUID.randomUUID().toString() : trace;
            String hash = RunJson.hash(json.object().put("question", question)
                    .set("documentIds", json.tree(documents)).toString());
            return checkDocuments(documents, context)
                    .then(repository.create(context, question, documents, key, hash, traceId)).map(this::view);
        });
    }

    public Mono<AgentRun> owned(UUID id, RequestContext context) {
        return Mono.defer(() -> {
            enabled();
            return lifecycle.maintain().then(repository.find(id))
                    .filter(run -> run.tenantId().equals(context.tenantId()) && run.actorId().equals(context.actorId()))
                    .switchIfEmpty(Mono.error(RunException.missing()))
                    .flatMap(run -> repository.documents(id).collectList()
                            .flatMap(ids -> checkDocuments(ids, context)).thenReturn(run));
        });
    }

    public Mono<JsonNode> get(UUID id, RequestContext context) {
        return owned(id, context).flatMap(run -> approvals.list(id).map(approvals::view).collectList()
                .flatMap(decisions -> approvals.executions(id).collectList().map(results -> {
                    ObjectNode response = (ObjectNode) view(run);
                    response.set("approvals", json.tree(decisions));
                    decisions.stream().filter(value -> value.path("status").asText().equals("PENDING")).findFirst()
                            .ifPresent(value -> response.set("pendingApproval", value));
                    response.set("actionResults", json.tree(results));
                    return (JsonNode) response;
                })).flatMap(response -> repository.documents(id).collectList().map(ids -> {
                    ((ObjectNode) response).put("question", run.question()).set("documentIds", json.tree(ids));
                    return response;
                })));
    }

    public Mono<JsonNode> events(UUID id, long after, RequestContext context) {
        if (after < 0) { return Mono.error(RunException.invalid("afterSequence must not be negative")); }
        return owned(id, context).flatMap(run -> repository.events(id, after).collectList().map(events -> {
            List<JsonNode> page = events.stream().limit(100).toList();
            return json.object().put("runId", id.toString()).put("traceId", run.traceId())
                    .put("executionMode", run.executionMode()).put("hasMore", events.size() > 100)
                    .put("nextSequence", page.isEmpty() ? after : page.get(page.size() - 1).get("sequence").asLong())
                    .set("events", json.tree(page));
        }));
    }

    public Mono<JsonNode> cancel(UUID id, RequestContext context) {
        return owned(id, context).then(repository.locked(id, run -> {
            if (!run.context().equals(context)) { return Mono.error(RunException.missing()); }
            return repository.documents(id).collectList().flatMap(ids -> checkDocuments(ids, context))
                    .then(lifecycle.requestCancel(run)).then(repository.find(id)).map(this::view);
        }));
    }

    public void workerAccess(String token) {
        enabled();
        if (token == null || token.length() > 512 || !RunJson.matches(properties.getWorkerToken(), token)) {
            throw new RunException(401, "WORKER_UNAUTHORIZED", "Invalid worker credentials");
        }
    }

    public Mono<JsonNode> claim(String workerToken) {
        return Mono.defer(() -> {
            workerAccess(workerToken);
            byte[] bytes = new byte[32];
            random.nextBytes(bytes);
            String token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
            return lifecycle.maintain().then(repository.claim(RunJson.hash(token)))
                    .flatMap(run -> repository.documents(run.id()).collectList().flatMap(ids -> checkDocuments(ids, run.context())
                            .then(approvals.snapshot(run.id())).map(snapshot -> {
                        ObjectNode assignment = json.object().put("schemaVersion", 1).put("runId", run.id().toString())
                                .put("traceId", run.traceId()).put("question", run.question()).put("claimToken", token)
                                .put("executionMode", run.executionMode()).put("provider", run.provider()).put("model", run.model())
                                .put("deadlineAt", run.deadlineAt().toString()).put("leaseSeconds", properties.getLease().toSeconds())
                                .put("maxTools", properties.getMaxTools()).put("maxRounds", properties.getMaxRounds())
                                .put("toolsUsed", run.toolCount()).put("roundsUsed", run.roundCount())
                                .put("recoveryCount", run.recoveryCount())
                                .put("promptVersion", "diagnostics-v1");
                        assignment.set("continuation", snapshot);
                        snapshot.path("actionResults").forEach(result -> {
                            if (result.path("status").asText().equals("PENDING")) {
                                assignment.put("pendingApprovalId", result.path("approvalId").asText());
                            }
                        });
                        assignment.set("documentIds", json.tree(ids));
                        log.info("agent_run_started runId={} traceId={} mode={}", run.id(), run.traceId(), run.executionMode());
                        return assignment;
                    })).onErrorResume(error -> repository.locked(run.id(), current -> current.running()
                            && run.claimHash().equals(current.claimHash()) ? lifecycle.fail(current,
                            error instanceof RunException re && re.status() == 404 ? "ACCESS_REVOKED" : "CONTINUATION_UNAVAILABLE") : Mono.empty())
                            .then(Mono.error(error))));
        });
    }

    public void authorize(AgentRun run, String token, boolean active) {
        enabled();
        if (token == null || token.length() > 128 || !RunJson.matches(run.claimHash(), RunJson.hash(token))) {
            throw new RunException(401, "CLAIM_UNAUTHORIZED", "Invalid claim credentials");
        }
        if (active && run.cancellationRequested()) {
            throw new RunException(409, "RUN_CANCELLED", "Cancellation has been requested");
        }
        if (active && !lifecycle.activeLease(run)) {
            throw new RunException(409, "CLAIM_INACTIVE", "Run claim is not active");
        }
    }

    public Mono<Void> heartbeat(UUID id, String token) {
        return repository.locked(id, run -> {
            authorize(run, token, false);
            // A pending cancellation must not interrupt an in-flight write's result bookkeeping.
            if (!lifecycle.activeLease(run)) {
                throw new RunException(409, run.cancellationRequested() ? "RUN_CANCELLED" : "CLAIM_INACTIVE", "Run is no longer active");
            }
            return repository.heartbeat(run);
        });
    }

    public Mono<Void> reserveRound(UUID id, String token, UUID reservation) {
        return repository.locked(id, run -> {
            authorize(run, token, true);
            return repository.reserveRound(run, reservation);
        });
    }

    public Mono<Void> complete(UUID id, String token, JsonNode report) {
        return repository.locked(id, run -> {
            authorize(run, token, false);
            if ("SUCCEEDED".equals(run.status()) && run.report().equals(report)) { return Mono.empty(); }
            authorize(run, token, true);
            return repository.documents(id).collectList().flatMap(ids -> checkDocuments(ids, run.context())
                    .then(approvals.hasUnfinished(id)).flatMap(unfinished -> unfinished
                            ? Mono.error(RunException.conflict("Approved action must finish before reporting"))
                            : repository.currentTools(run).collectList()).flatMap(calls -> {
                        if (calls.stream().anyMatch(call -> "RUNNING".equals(call.status()))) {
                            return Mono.error(RunException.conflict("A diagnostic tool is still running"));
                        }
                        validator.validate(report, ids, calls);
                        return repository.finish(run, report, null);
                    })).doOnSuccess(ignored -> log.info("agent_run_completed runId={} traceId={}", id, run.traceId()));
        });
    }

    public Mono<Void> fail(UUID id, String token, String code) {
        if (!Set.of("MODEL_ERROR", "MODEL_TIMEOUT", "INVALID_REPORT", "TOOL_FAILED", "BUDGET_EXCEEDED",
                "WORKER_ERROR", "RUN_DEADLINE", "CONFIGURATION_ERROR").contains(code)) {
            return Mono.error(RunException.invalid("Unknown worker failure code"));
        }
        return repository.locked(id, run -> {
            authorize(run, token, false);
            if (run.status().equals("CANCELLED") || run.status().equals("RECOVERY_REQUIRED")) { return Mono.empty(); }
            if ("FAILED".equals(run.status()) && code.equals(run.errorCode())) { return Mono.empty(); }
            if (!run.running()) { return Mono.error(RunException.conflict("Run is no longer active")); }
            return lifecycle.fail(run, code);
        });
    }

    public Mono<Void> checkDocuments(List<UUID> documents, RequestContext context) {
        return Flux.fromIterable(documents).concatMap(id -> diagnostics.accessible(id, context)).then();
    }

    public JsonNode view(AgentRun run) {
        ObjectNode view = json.object().put("runId", run.id().toString()).put("traceId", run.traceId())
                .put("status", run.status()).put("executionMode", run.executionMode()).put("provider", run.provider())
                .put("model", run.model()).put("createdAt", run.createdAt().toString())
                .put("updatedAt", run.updatedAt().toString()).put("statusUrl", "/api/v1/agent/runs/" + run.id());
        view.put("recoveryCount", run.recoveryCount()).put("cancellationRequested", run.cancellationRequested())
                .put("cancellationPending", run.cancellationRequested() && !run.terminal());
        if (run.report() != null) { view.set("report", run.report()); }
        if (run.errorCode() != null) { view.put("errorCode", run.errorCode()); }
        return view;
    }

    private void enabled() {
        if (!properties.isEnabled()) { throw new RunException(404, "AGENT_RUNS_DISABLED", "Agent runs are disabled"); }
    }

}
