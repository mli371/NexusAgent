package com.nexusagent.agentrun;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.Map;
import java.util.UUID;

import com.nexusagent.agentrun.api.AgentRunController;
import com.nexusagent.agentrun.api.AgentRunExceptionHandler;
import com.nexusagent.agentrun.application.AgentRunService;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Mono;

class AgentRunErrorTest {
    @Test
    void databaseFailureReturnsSanitized503WithoutAcceptingTheRun() {
        AgentRunService service = mock(AgentRunService.class);
        when(service.create(any(), any(), isNull(), isNull()))
                .thenReturn(Mono.error(new DataAccessResourceFailureException("private connection details")));
        WebTestClient.bindToController(new AgentRunController(service, mock(com.nexusagent.agentrun.approval.ApprovalService.class)))
                .controllerAdvice(new AgentRunExceptionHandler()).build()
                .post().uri("/api/v1/agent/runs")
                .header("X-Tenant-Id", "tenant-a").header("X-Actor-Id", "alice")
                .bodyValue(Map.of("question", "Inspect status", "documentIds", new String[]{UUID.randomUUID().toString()}))
                .exchange().expectStatus().isEqualTo(503).expectBody()
                .jsonPath("$.code").isEqualTo("STORAGE_UNAVAILABLE")
                .jsonPath("$.message").isEqualTo("Run storage temporarily unavailable")
                .jsonPath("$.runId").doesNotExist();
    }
}
