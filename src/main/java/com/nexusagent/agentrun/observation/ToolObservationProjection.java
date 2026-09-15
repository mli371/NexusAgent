package com.nexusagent.agentrun.observation;

import java.nio.charset.StandardCharsets;
import java.util.Set;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.nexusagent.agentrun.application.RunJson;
import org.springframework.stereotype.Component;

@Component
public class ToolObservationProjection {
    public static final int MAX_BYTES = 32 * 1024;
    private static final Set<String> TOOLS = Set.of("inspect_document", "list_ingestion_jobs", "propose_retry");
    private final RunJson json;

    public ToolObservationProjection(RunJson json) { this.json = json; }

    public ObjectNode summary(ToolObservation call) {
        ObjectNode result = json.object().put("observationId", call.id().toString())
                .put("invocationId", call.invocationId().toString())
                .put("toolName", TOOLS.contains(call.toolName()) ? call.toolName() : "unsupported_tool")
                .put("attempt", call.attempt()).put("retryOf", call.retryOf() == null ? null : call.retryOf().toString())
                .put("status", call.status()).put("createdAt", call.createdAt().toString());
        result.put("finishedAt", call.finishedAt() == null ? null : call.finishedAt().toString());
        return result;
    }

    public ObjectNode detail(ToolObservation call, String runId, String traceId) {
        ObjectNode result = summary(call).put("schemaVersion", 1).put("runId", runId).put("traceId", traceId);
        ArrayNode omitted = result.putArray("omittedFields");
        if (!TOOLS.contains(call.toolName())) {
            result.putNull("arguments").putNull("result");
            omitted.add("arguments").add("result");
        } else {
            result.set("arguments", scalars(call.arguments(), "arguments", omitted,
                    call.toolName().equals("propose_retry") ? new String[]{"documentId", "action", "reason"} : new String[]{"documentId"}));
            if (call.result() == null) { result.putNull("result"); }
            else {
                ObjectNode envelope = scalars(call.result(), "result", omitted,
                        "schemaVersion", "invocationId", "observationId", "status", "observedAt");
                if (call.result().has("error")) {
                    envelope.set("error", scalars(call.result().get("error"), "result.error", omitted, "code", "retryable"));
                }
                if (call.result().has("data")) { envelope.set("data", data(call.toolName(), call.result().get("data"), omitted)); }
                result.set("result", envelope);
            }
        }
        result.put("payloadOmitted", !omitted.isEmpty());
        if (result.toString().getBytes(StandardCharsets.UTF_8).length > MAX_BYTES) {
            result.putNull("arguments").putNull("result").put("payloadOmitted", true);
            omitted.removeAll().add("arguments").add("result");
        }
        return result;
    }

    private ObjectNode data(String tool, JsonNode source, ArrayNode omitted) {
        if (tool.equals("inspect_document")) {
            return scalars(source, "result.data", omitted, "documentId", "documentStatus", "extractionSupported",
                    "parentChunkCount", "childChunkCount", "embeddedChildChunkCount", "mismatchedEmbeddingCount",
                    "embeddingProvider", "embeddingModel", "embeddingDimension", "condition");
        }
        if (tool.equals("propose_retry")) {
            ObjectNode result = scalars(source, "result.data", omitted, "approvalId", "documentId", "action", "reason",
                    "status", "stateFingerprint", "expiresAt", "requestedBy", "decidedBy");
            result.set("arguments", scalars(source.path("arguments"), "result.data.arguments", omitted,
                    "documentId", "action", "force", "missingOnly"));
            return result;
        }
        ObjectNode result = scalars(source, "result.data", omitted, "documentId", "truncated");
        ArrayNode jobs = result.putArray("jobs");
        JsonNode rows = source.path("jobs");
        if (rows.isArray()) {
            for (int i = 0; i < Math.min(rows.size(), 10); i++) {
                jobs.add(scalars(rows.get(i), "result.data.jobs[" + i + "]", omitted,
                        "id", "jobType", "status", "started_at", "finished_at", "created_at", "errorCategory"));
            }
            if (rows.size() > 10) { omitted.add("result.data.jobs[10:]"); result.put("truncated", true); }
        }
        return result;
    }

    // Only scalar fields cross the browser boundary; new nested payloads require an explicit review.
    private ObjectNode scalars(JsonNode source, String path, ArrayNode omitted, String... names) {
        ObjectNode result = json.object();
        if (source == null || !source.isObject()) { omitted.add(path); return result; }
        for (String name : names) {
            JsonNode value = source.get(name);
            if (value == null) { continue; }
            if (value.isContainerNode() || (value.isTextual() && value.textValue().length() > 1024)) {
                omitted.add(path + "." + name);
            } else { result.set(name, value); }
        }
        return result;
    }
}
