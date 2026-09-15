package com.nexusagent.agentrun.approval;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.Set;
import java.util.UUID;

import com.fasterxml.jackson.databind.JsonNode;
import com.nexusagent.agentrun.application.AgentRunProperties;
import com.nexusagent.agentrun.application.AgentRunService;
import com.nexusagent.agentrun.application.RunException;
import com.nexusagent.agentrun.application.RunJson;
import com.nexusagent.agentrun.domain.ToolCall;
import com.nexusagent.agentrun.domain.AgentRun;
import com.nexusagent.agentrun.repository.AgentRunRepository;
import com.nexusagent.common.context.RequestContext;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

@Service
public class ApprovalService {
    private final AgentRunRepository repository;
    private final AgentRunService runs;
    private final ApprovalRepository approvals;
    private final RetryPolicy policy;
    private final AgentRunProperties properties;
    private final RunJson json;
    private final Clock clock;

    public ApprovalService(AgentRunRepository repository, AgentRunService runs, ApprovalRepository approvals,
                           RetryPolicy policy, AgentRunProperties properties, RunJson json, Clock clock) {
        this.repository = repository; this.runs = runs; this.approvals = approvals;
        this.policy = policy; this.properties = properties; this.json = json; this.clock = clock;
    }

    public Mono<JsonNode> propose(UUID runId, String token, JsonNode body) {
        if (body.has("retryOf")) { return Mono.error(RunException.invalid("Proposals cannot use read retryOf")); }
        UUID invocation = RunJson.uuid(body, "invocationId");
        JsonNode args = body.get("arguments");
        RunJson.fields(args, "documentId", "action", "reason");
        UUID document = RunJson.uuid(args, "documentId");
        String action = RunJson.text(args, "action", 40);
        String reason = RunJson.text(args, "reason", 1000);
        JsonNode canonical = policy.arguments(document, action);
        JsonNode request = json.object().put("documentId", document.toString()).put("action", action).put("reason", reason);
        return repository.locked(runId, run -> {
            runs.authorize(run, token, false);
            return repository.documents(runId).collectList().flatMap(ids -> {
                if (!ids.contains(document)) { return Mono.error(RunException.missing()); }
                return runs.checkDocuments(ids, run.context()).then(repository.tool(runId, invocation))
                        .flatMap(call -> {
                            if (!call.toolName().equals("propose_retry") || !call.arguments().equals(request)) {
                                return Mono.error(RunException.conflict("Invocation ID has different arguments"));
                            }
                            return Mono.just(call.result());
                        }).switchIfEmpty(Mono.defer(() -> {
                            runs.authorize(run, token, true);
                            if (run.toolCount() + 1 + 2 * ids.size() > properties.getMaxTools()
                                    || run.roundCount() >= properties.getMaxRounds()
                                    || run.eventSequence() + 8 >= properties.getMaxEvents()) {
                                throw new RunException(409, "BUDGET_EXCEEDED", "Insufficient budget for proposal and reinspection");
                            }
                            return requireObservations(run, document)
                                    .then(repository.startTool(run, invocation, "propose_retry", request, null))
                                    .flatMap(call -> saveProposal(run, call, document, action, canonical, reason));
                        }));
            });
        });
    }

    private Mono<Void> requireObservations(AgentRun run, UUID document) {
        return approvals.hasUnfinished(run.id()).flatMap(unfinished -> {
            if (unfinished) { return Mono.error(RunException.conflict("Approved action must finish first")); }
            return repository.currentTools(run).collectList().flatMap(calls -> {
                Set<String> observed = calls.stream().filter(call -> call.status().equals("SUCCEEDED")
                                && document.toString().equals(call.arguments().path("documentId").asText()))
                        .map(ToolCall::toolName).collect(java.util.stream.Collectors.toSet());
                if (calls.stream().anyMatch(call -> call.status().equals("RUNNING"))
                        || !observed.containsAll(Set.of("inspect_document", "list_ingestion_jobs"))) {
                    return Mono.error(RunException.conflict("Inspect the document and its jobs before proposing"));
                }
                return Mono.empty();
            });
        });
    }

    private Mono<JsonNode> saveProposal(AgentRun run, ToolCall call, UUID document, String action,
                                        JsonNode canonical, String reason) {
        return policy.inspect(run, document, action).flatMap(state -> {
            state.requireEligible();
            return approvals.create(run, document, action, canonical, state.fingerprint(), reason)
                    .flatMap(approval -> repository.finishTool(call, envelope(call, "SUCCEEDED").set("data", approvals.view(approval)))
                            .flatMap(result -> approvals.pause(approval).thenReturn(result)));
        }).onErrorResume(RunException.class, error -> repository.finishTool(call,
                envelope(call, "FAILED").set("error", json.object().put("code", error.code())
                        .put("message", error.getMessage()).put("retryable", false))));
    }

    public Mono<JsonNode> decide(UUID runId, UUID approvalId, String decision, RequestContext context) {
        if (!Set.of("APPROVE", "REJECT").contains(decision)) { return Mono.error(RunException.invalid("decision must be APPROVE or REJECT")); }
        String status = decision.equals("APPROVE") ? "APPROVED" : "REJECTED";
        return runs.owned(runId, context).then(repository.locked(runId, run -> {
            if (!run.context().equals(context)) { return Mono.error(RunException.missing()); }
            return repository.documents(runId).collectList().flatMap(ids -> runs.checkDocuments(ids, context))
                    .then(approvals.find(runId, approvalId)).switchIfEmpty(Mono.error(RunException.missing()))
                    .flatMap(approval -> {
                        if (!approval.requestedBy().equals(context.actorId())) { return Mono.error(RunException.missing()); }
                        if (approval.status().equals(status) && context.actorId().equals(approval.decidedBy())) {
                            return Mono.just(approvals.view(approval));
                        }
                        if (!approval.status().equals("PENDING") || !run.status().equals("WAITING_APPROVAL")
                                || !approval.expiresAt().isAfter(OffsetDateTime.now(clock))) {
                            return Mono.error(RunException.conflict("Approval is no longer pending or has expired"));
                        }
                        return approvals.decide(approval, status, context.actorId())
                                .then(approvals.find(runId, approvalId)).map(approvals::view);
                    });
        }));
    }

    private com.fasterxml.jackson.databind.node.ObjectNode envelope(ToolCall call, String status) {
        return json.object().put("schemaVersion", 1).put("invocationId", call.invocationId().toString())
                .put("observationId", call.id().toString()).put("status", status)
                .put("observedAt", OffsetDateTime.now(clock).toString());
    }
}
