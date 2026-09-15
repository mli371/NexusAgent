package com.nexusagent.agentrun.api;

import java.util.UUID;

import com.fasterxml.jackson.databind.JsonNode;
import com.nexusagent.agentrun.application.AgentRunService;
import com.nexusagent.agentrun.application.RunJson;
import com.nexusagent.agentrun.approval.ApprovedRetryService;
import com.nexusagent.agentrun.tools.AgentToolService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

@RestController
@RequestMapping("/internal/agent-worker")
public class AgentWorkerController {
    private final AgentRunService runs;
    private final AgentToolService tools;
    private final ApprovedRetryService retries;

    public AgentWorkerController(AgentRunService runs, AgentToolService tools, ApprovedRetryService retries) {
        this.runs = runs;
        this.tools = tools;
        this.retries = retries;
    }

    @PostMapping("/runs/{runId}/approved-retries/{approvalId}")
    public Mono<JsonNode> execute(@PathVariable UUID runId, @PathVariable UUID approvalId,
                                  @RequestHeader(value = "X-Claim-Token", required = false) String token) {
        return retries.execute(runId, token, approvalId);
    }

    @PostMapping("/runs/{runId}/model-rounds/{reservationId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public Mono<Void> reserveRound(@PathVariable UUID runId, @PathVariable UUID reservationId,
                                   @RequestHeader(value = "X-Claim-Token", required = false) String token) {
        return runs.reserveRound(runId, token, reservationId);
    }

    @PostMapping("/claims")
    public Mono<ResponseEntity<JsonNode>> claim(@RequestHeader(value = "X-Worker-Token", required = false) String token) {
        return runs.claim(token).map(ResponseEntity::ok).defaultIfEmpty(ResponseEntity.noContent().build());
    }

    @PostMapping("/runs/{runId}/heartbeat")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public Mono<Void> heartbeat(@PathVariable UUID runId,
                               @RequestHeader(value = "X-Claim-Token", required = false) String token) {
        return runs.heartbeat(runId, token);
    }

    @PostMapping("/runs/{runId}/tools")
    public Mono<JsonNode> tool(@PathVariable UUID runId, @RequestBody JsonNode body,
                              @RequestHeader(value = "X-Claim-Token", required = false) String token) {
        return tools.invoke(runId, token, body);
    }

    @GetMapping("/runs/{runId}/tools/{invocationId}")
    public Mono<JsonNode> result(@PathVariable UUID runId, @PathVariable UUID invocationId,
                                @RequestHeader(value = "X-Claim-Token", required = false) String token) {
        return tools.result(runId, token, invocationId);
    }

    @PostMapping("/runs/{runId}/complete")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public Mono<Void> complete(@PathVariable UUID runId, @RequestBody JsonNode report,
                               @RequestHeader(value = "X-Claim-Token", required = false) String token) {
        return runs.complete(runId, token, report);
    }

    @PostMapping("/runs/{runId}/fail")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public Mono<Void> fail(@PathVariable UUID runId, @RequestBody JsonNode body,
                          @RequestHeader(value = "X-Claim-Token", required = false) String token) {
        RunJson.fields(body, "code");
        return runs.fail(runId, token, RunJson.text(body, "code", 60));
    }
}
