package com.nexusagent.agentrun;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.UUID;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.nexusagent.agentrun.application.RunJson;
import com.nexusagent.agentrun.observation.ToolObservation;
import com.nexusagent.agentrun.observation.ToolObservationProjection;
import org.junit.jupiter.api.Test;

class ToolObservationProjectionTest {
    final ObjectMapper mapper = new ObjectMapper();
    final ToolObservationProjection projection = new ToolObservationProjection(new RunJson(mapper));

    @Test void onlyReviewedFieldsAreReturnedIncludingNestedPayloads() {
        var args = mapper.createObjectNode().put("documentId", "document").put("claimToken", "secret");
        var payload = mapper.createObjectNode().put("status", "SUCCEEDED").put("workerToken", "secret");
        payload.putObject("data").put("condition", "HEALTHY").put("rawText", "secret").putObject("embeddingModel").put("secret", "nested");
        payload.putObject("error").put("code", "TEST").put("message", "secret stack trace");
        var result = projection.detail(call("inspect_document", args, payload), "run", "trace");
        assertThat(result.toString()).doesNotContain("secret", "claimToken", "workerToken", "rawText", "message");
        assertThat(result.path("result").path("data").path("condition").asText()).isEqualTo("HEALTHY");
        assertThat(result.path("payloadOmitted").asBoolean()).isTrue();
    }

    @Test void unknownToolsCannotExposeFuturePayloads() {
        var value = mapper.createObjectNode().put("rawDocument", "secret");
        var result = projection.detail(call("future_tool", value, value), "run", "trace");
        assertThat(result.path("toolName").asText()).isEqualTo("unsupported_tool");
        assertThat(result.path("arguments").isNull()).isTrue();
        assertThat(result.path("result").isNull()).isTrue();
        assertThat(result.toString()).doesNotContain("secret", "future_tool");
    }

    @Test void oversizedProjectionIsOmittedAsValidJsonRatherThanClipped() throws Exception {
        var payload = mapper.createObjectNode().put("status", "SUCCEEDED");
        var jobs = payload.putObject("data").putArray("jobs");
        for (int i = 0; i < 10; i++) {
            var job = jobs.addObject();
            for (String name : new String[]{"id", "jobType", "status", "started_at", "finished_at", "created_at", "errorCategory"}) {
                job.put(name, "x".repeat(1024));
            }
        }
        var result = projection.detail(call("list_ingestion_jobs", mapper.createObjectNode(), payload), "run", "trace");
        assertThat(result.path("payloadOmitted").asBoolean()).isTrue();
        assertThat(result.path("result").isNull()).isTrue();
        byte[] bytes = result.toString().getBytes(StandardCharsets.UTF_8);
        assertThat(bytes.length).isLessThanOrEqualTo(ToolObservationProjection.MAX_BYTES);
        assertThat(mapper.readTree(bytes)).isEqualTo(result);
    }

    @Test void nullResultsAndLongScalarsAreNotInvented() {
        var result = projection.detail(call("propose_retry", mapper.createObjectNode().put("reason", "x".repeat(1025)), null), "run", "trace");
        assertThat(result.path("result").isNull()).isTrue();
        assertThat(result.path("arguments").has("reason")).isFalse();
        assertThat(result.path("omittedFields").toString()).contains("arguments.reason");
    }

    private ToolObservation call(String name, ObjectNode args, ObjectNode result) {
        return new ToolObservation(UUID.randomUUID(), UUID.randomUUID(), name, 1, null,
                result == null ? "RUNNING" : "SUCCEEDED", OffsetDateTime.now(), null, args, result);
    }
}
