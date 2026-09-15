package com.nexusagent.agentrun.observation;

import java.util.UUID;

import com.fasterxml.jackson.databind.JsonNode;
import com.nexusagent.agentrun.application.AgentRunService;
import com.nexusagent.agentrun.application.RunException;
import com.nexusagent.agentrun.application.RunJson;
import com.nexusagent.common.context.RequestContext;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

@Service
public class ToolObservationService {
    private final AgentRunService runs;
    private final ToolObservationRepository repository;
    private final ToolObservationProjection projection;
    private final RunJson json;

    public ToolObservationService(AgentRunService runs, ToolObservationRepository repository,
                                  ToolObservationProjection projection, RunJson json) {
        this.runs = runs; this.repository = repository; this.projection = projection; this.json = json;
    }

    public Mono<JsonNode> list(UUID runId, RequestContext context, int limit, int offset) {
        if (limit < 1 || limit > 50 || offset < 0) { return Mono.error(RunException.invalid("limit must be 1..50 and offset must be non-negative")); }
        return runs.owned(runId, context).flatMap(run -> repository.list(runId, limit + 1, offset).collectList()
                .map(rows -> json.object().put("schemaVersion", 1).put("runId", runId.toString()).put("traceId", run.traceId())
                        .put("hasMore", rows.size() > limit).put("nextOffset", offset + Math.min(rows.size(), limit))
                        .set("tools", json.tree(rows.stream().limit(limit).map(projection::summary).toList()))));
    }

    public Mono<JsonNode> get(UUID runId, UUID observation, RequestContext context) {
        return runs.owned(runId, context).flatMap(run -> repository.find(runId, observation)
                .switchIfEmpty(Mono.error(RunException.missing()))
                .map(call -> projection.detail(call, runId.toString(), run.traceId())));
    }
}
