package com.nexusagent.agentrun.tools;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeoutException;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.nexusagent.agentrun.application.AgentRunProperties;
import com.nexusagent.agentrun.application.AgentRunService;
import com.nexusagent.agentrun.application.RunException;
import com.nexusagent.agentrun.application.RunJson;
import com.nexusagent.agentrun.domain.AgentRun;
import com.nexusagent.agentrun.approval.ApprovalService;
import com.nexusagent.agentrun.approval.ApprovalRepository;
import com.nexusagent.agentrun.domain.ToolCall;
import com.nexusagent.agentrun.repository.AgentRunRepository;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

@Service
public class AgentToolService {
    private final AgentRunRepository repository;
    private final AgentRunService runs;
    private final DocumentDiagnosticsService diagnostics;
    private final AgentRunProperties properties;
    private final RunJson json;
    private final Clock clock;
    private final ApprovalService approvals;
    private final ApprovalRepository approvalRepository;

    public AgentToolService(AgentRunRepository repository, AgentRunService runs, DocumentDiagnosticsService diagnostics,
                            AgentRunProperties properties, RunJson json, Clock clock,
                            ApprovalService approvals, ApprovalRepository approvalRepository) {
        this.repository = repository;
        this.runs = runs;
        this.diagnostics = diagnostics;
        this.properties = properties;
        this.json = json;
        this.clock = clock;
        this.approvals = approvals;
        this.approvalRepository = approvalRepository;
    }

    public Mono<JsonNode> invoke(UUID runId, String token, JsonNode body) {
        return Mono.defer(() -> {
            RunJson.limit(body, 4096);
            RunJson.fields(body, "schemaVersion", "invocationId", "toolName", "arguments", "retryOf");
            if (!body.path("schemaVersion").isInt() || body.path("schemaVersion").asInt() != 1) {
                throw RunException.invalid("Tool schemaVersion must be 1");
            }
            UUID invocation = RunJson.uuid(body, "invocationId");
            String name = RunJson.text(body, "toolName", 60);
            if (name.equals("propose_retry")) { return approvals.propose(runId, token, body); }
            if (!Set.of("inspect_document", "list_ingestion_jobs").contains(name)) {
                throw RunException.invalid("Tool is not allowed");
            }
            JsonNode args = body.get("arguments");
            RunJson.fields(args, "documentId");
            UUID document = RunJson.uuid(args, "documentId");
            JsonNode canonicalArgs = json.object().put("documentId", document.toString());
            UUID retryOf = body.has("retryOf") ? RunJson.uuid(body, "retryOf") : null;

            return repository.locked(runId, run -> {
                runs.authorize(run, token, true);
                return repository.documents(runId).collectList().flatMap(ids -> {
                    if (!ids.contains(document)) { return Mono.error(RunException.missing()); }
                    return approvalRepository.hasUnfinished(runId).flatMap(unfinished -> unfinished
                            ? Mono.error(RunException.conflict("Approved action must finish before diagnostic tools"))
                            : diagnostics.accessible(document, run.context()))
                            .then(repository.tool(runId, invocation))
                            .flatMap(call -> {
                                if (!call.toolName().equals(name) || !call.argumentsHash().equals(RunJson.hash(canonicalArgs.toString()))) {
                                    return Mono.error(RunException.conflict("Invocation ID has different arguments"));
                                }
                                return Mono.just(new Started(run, call, false));
                            }).switchIfEmpty(Mono.defer(() -> {
                                if (run.toolCount() >= properties.getMaxTools()
                                        || run.eventSequence() + 3 >= properties.getMaxEvents()) {
                                    throw new RunException(409, "BUDGET_EXCEEDED", "Tool budget exhausted");
                                }
                                return validateRetry(runId, retryOf, name, canonicalArgs)
                                        .then(repository.startTool(run, invocation, name, canonicalArgs, retryOf))
                                        .map(call -> new Started(run, call, true));
                            }));
                });
            }).flatMap(started -> {
                if (!started.execute()) { return Mono.just(resultOrRunning(started.call())); }
                Mono<JsonNode> read = name.equals("inspect_document")
                        ? diagnostics.inspect(document, started.run().context()) : diagnostics.jobs(document, started.run().context());
                // No transaction or row lock spans dependency I/O.
                return read.timeout(properties.getToolTimeout())
                        .map(data -> (JsonNode) envelope(started.call(), "SUCCEEDED").set("data", data))
                        .map(result -> { RunJson.limit(result, 8192); return (JsonNode) result; })
                        .onErrorResume(error -> Mono.just(failure(started.call(), error)))
                        .flatMap(result -> repository.locked(runId, run -> {
                            runs.authorize(run, token, true);
                            return diagnostics.accessible(document, run.context())
                                    .then(repository.finishTool(started.call(), result));
                        }));
            });
        });
    }

    private Mono<Void> validateRetry(UUID runId, UUID retryOf, String name, JsonNode args) {
        if (retryOf == null) { return Mono.empty(); }
        return repository.tool(runId, retryOf).switchIfEmpty(Mono.error(RunException.invalid("retryOf is not a saved call")))
                .flatMap(call -> {
                    if (!"FAILED".equals(call.status()) || !call.toolName().equals(name) || !call.arguments().equals(args)
                            || !call.result().path("error").path("retryable").asBoolean()) {
                        return Mono.error(RunException.invalid("This call cannot be retried"));
                    }
                    return repository.reserveReadRetry(runId, retryOf);
                });
    }

    public Mono<JsonNode> result(UUID runId, String token, UUID invocation) {
        return repository.locked(runId, run -> {
            runs.authorize(run, token, false);
            return repository.documents(runId).collectList().flatMap(ids -> runs.checkDocuments(ids, run.context()))
                    .then(repository.tool(runId, invocation)).switchIfEmpty(Mono.error(RunException.missing()))
                    .map(this::resultOrRunning);
        });
    }

    private JsonNode resultOrRunning(ToolCall call) {
        return call.result() == null ? envelope(call, "RUNNING") : call.result();
    }

    private ObjectNode envelope(ToolCall call, String status) {
        return json.object().put("schemaVersion", 1).put("invocationId", call.invocationId().toString())
                .put("observationId", call.id().toString()).put("status", status)
                .put("observedAt", OffsetDateTime.now(clock).toString());
    }

    private JsonNode failure(ToolCall call, Throwable error) {
        boolean transientFailure = error instanceof TimeoutException || error instanceof TransientDataAccessException;
        String code = transientFailure ? "TRANSIENT_DEPENDENCY" : error instanceof RunException run ? run.code() : "TOOL_ERROR";
        return envelope(call, "FAILED").set("error", json.object().put("code", code)
                .put("message", transientFailure ? "Diagnostic dependency temporarily unavailable" : "Diagnostic tool failed")
                .put("retryable", transientFailure));
    }

    private record Started(AgentRun run, ToolCall call, boolean execute) { }
}
