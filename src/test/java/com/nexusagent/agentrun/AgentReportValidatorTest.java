package com.nexusagent.agentrun;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.UUID;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.nexusagent.agentrun.application.AgentReportValidator;
import com.nexusagent.agentrun.application.RunException;
import com.nexusagent.agentrun.application.RunJson;
import com.nexusagent.agentrun.domain.ToolCall;
import org.junit.jupiter.api.Test;

class AgentReportValidatorTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private final AgentReportValidator validator = new AgentReportValidator();

    @Test
    void requiresBothToolsRejectsWrongConditionsAndCannotAcceptForeignObservations() {
        UUID document = UUID.randomUUID();
        UUID run = UUID.randomUUID();
        ToolCall inspect = call(run, document, "inspect_document");
        ToolCall jobs = call(run, document, "list_ingestion_jobs");
        ObjectNode report = report(document, inspect, jobs);
        validator.validate(report, List.of(document), List.of(inspect, jobs));
        assertThatThrownBy(() -> validator.validate(report, List.of(document), List.of(inspect)))
                .isInstanceOf(RunException.class);
        ((ObjectNode) report.path("findings").get(0)).put("condition", "REPAIRED");
        assertThatThrownBy(() -> validator.validate(report, List.of(document), List.of(inspect, jobs)))
                .hasMessageContaining("contradicts");
        assertThatThrownBy(() -> validator.validate(report(document, inspect, jobs), List.of(UUID.randomUUID()), List.of(inspect, jobs)))
                .hasMessageContaining("out-of-scope");
    }

    @Test
    void rejectsOversizedReportsAndUnknownFields() {
        UUID document = UUID.randomUUID();
        ToolCall inspect = call(UUID.randomUUID(), document, "inspect_document");
        ToolCall jobs = call(inspect.runId(), document, "list_ingestion_jobs");
        ObjectNode report = report(document, inspect, jobs);
        report.put("summary", "x".repeat(40000));
        assertThatThrownBy(() -> validator.validate(report, List.of(document), List.of(inspect, jobs)))
                .isInstanceOf(RunException.class).hasMessageContaining("byte limit");
        assertThatThrownBy(() -> RunJson.fields(mapper.createObjectNode().put("tenantId", "other"), "documentId"))
                .hasMessageContaining("Unexpected field");
    }

    private ToolCall call(UUID run, UUID document, String name) {
        ObjectNode args = mapper.createObjectNode().put("documentId", document.toString());
        ObjectNode result = mapper.createObjectNode();
        result.set("data", mapper.createObjectNode().put("condition", "HEALTHY"));
        return new ToolCall(UUID.randomUUID(), run, UUID.randomUUID(), name, RunJson.hash(args.toString()), args, "SUCCEEDED", result);
    }

    private ObjectNode report(UUID document, ToolCall inspect, ToolCall jobs) {
        ObjectNode finding = mapper.createObjectNode().put("documentId", document.toString()).put("condition", "HEALTHY")
                .put("explanation", "Observed current metadata.");
        finding.set("observationIds", mapper.valueToTree(List.of(inspect.id(), jobs.id())));
        ObjectNode report = mapper.createObjectNode().put("schemaVersion", 1).put("summary", "Read-only diagnostics");
        report.set("findings", mapper.valueToTree(List.of(finding)));
        report.set("unresolved", mapper.createArrayNode());
        return report;
    }
}
