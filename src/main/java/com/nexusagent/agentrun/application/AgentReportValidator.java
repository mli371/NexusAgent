package com.nexusagent.agentrun.application;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import com.fasterxml.jackson.databind.JsonNode;
import com.nexusagent.agentrun.domain.ToolCall;
import org.springframework.stereotype.Component;

@Component
public class AgentReportValidator {
    public void validate(JsonNode report, List<UUID> documents, List<ToolCall> calls) {
        RunJson.limit(report, 32768);
        RunJson.fields(report, "schemaVersion", "summary", "findings", "unresolved");
        if (!report.path("schemaVersion").isInt() || report.path("schemaVersion").asInt() != 1) {
            throw RunException.invalid("Report schemaVersion must be 1");
        }
        RunJson.text(report, "summary", 4000);
        JsonNode findings = report.get("findings");
        if (findings == null || !findings.isArray() || findings.size() != documents.size()) {
            throw RunException.invalid("Report must contain one finding per requested document");
        }
        Map<UUID, ToolCall> observations = calls.stream().filter(call -> "SUCCEEDED".equals(call.status()))
                .collect(Collectors.toMap(ToolCall::id, Function.identity()));
        Set<UUID> seen = new HashSet<>();
        for (JsonNode finding : findings) {
            RunJson.fields(finding, "documentId", "observationIds", "condition", "explanation", "proposedNextAction");
            UUID document = RunJson.uuid(finding, "documentId");
            if (!documents.contains(document) || !seen.add(document)) {
                throw RunException.invalid("Finding contains a duplicate or out-of-scope document");
            }
            String condition = RunJson.text(finding, "condition", 80);
            RunJson.text(finding, "explanation", 2000);
            if (finding.has("proposedNextAction") && !Set.of("NONE", "CHUNK", "EMBED_MISSING", "MANUAL_REVIEW")
                    .contains(RunJson.text(finding, "proposedNextAction", 40))) {
                throw RunException.invalid("Unsupported advisory action");
            }
            JsonNode refs = finding.get("observationIds");
            if (refs == null || !refs.isArray() || refs.size() < 2 || refs.size() > 10) {
                throw RunException.invalid("Each finding requires saved inspect and job observations");
            }
            Set<String> tools = new HashSet<>();
            for (JsonNode ref : refs) {
                UUID id;
                try { id = UUID.fromString(ref.textValue()); }
                catch (Exception e) { throw RunException.invalid("Invalid observation reference"); }
                ToolCall call = observations.get(id);
                if (call == null || !document.toString().equals(call.arguments().path("documentId").asText())) {
                    throw RunException.invalid("Observation does not belong to this document and run");
                }
                tools.add(call.toolName());
                if (call.toolName().equals("inspect_document")
                        && !condition.equals(call.result().path("data").path("condition").asText())) {
                    throw RunException.invalid("Finding condition contradicts its saved observation");
                }
            }
            if (!tools.containsAll(Set.of("inspect_document", "list_ingestion_jobs"))) {
                throw RunException.invalid("Both diagnostic tools must be observed for each document");
            }
        }
        JsonNode unresolved = report.get("unresolved");
        if (unresolved == null || !unresolved.isArray() || unresolved.size() > 20) {
            throw RunException.invalid("unresolved must be an array with at most 20 items");
        }
        for (JsonNode item : unresolved) {
            if (!item.isTextual() || item.textValue().length() > 1000) {
                throw RunException.invalid("Invalid unresolved item");
            }
        }
    }
}
