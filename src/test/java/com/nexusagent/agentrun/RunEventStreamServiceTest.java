package com.nexusagent.agentrun;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.UUID;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexusagent.agentrun.api.AgentRunExceptionHandler;
import com.nexusagent.agentrun.api.RunEventStreamController;
import com.nexusagent.agentrun.application.AgentRunProperties;
import com.nexusagent.agentrun.application.AgentRunService;
import com.nexusagent.agentrun.application.RunEventStreamService;
import com.nexusagent.agentrun.application.RunJson;
import com.nexusagent.agentrun.domain.AgentRun;
import com.nexusagent.agentrun.repository.AgentRunRepository;
import com.nexusagent.common.context.RequestContext;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

class RunEventStreamServiceTest {
    private final UUID id = UUID.randomUUID();
    private final RequestContext owner = new RequestContext("tenant-a", "alice");
    private final AgentRunService runs = mock(AgentRunService.class);
    private final AgentRunRepository repository = mock(AgentRunRepository.class);
    private final RunJson json = new RunJson(new ObjectMapper());
    private final RunEventStreamService service = new RunEventStreamService(runs, repository, new AgentRunProperties(), json);

    @Test void storageFailureBeforeHeadersRemainsSanitizedHttp503() {
        when(runs.owned(id, owner)).thenReturn(Mono.error(new DataAccessResourceFailureException("private SQL details")));
        WebTestClient.bindToController(new RunEventStreamController(service)).controllerAdvice(new AgentRunExceptionHandler()).build()
                .get().uri("/api/v1/agent/runs/" + id + "/events/stream")
                .header("X-Tenant-Id", owner.tenantId()).header("X-Actor-Id", owner.actorId())
                .exchange().expectStatus().isEqualTo(503).expectBody()
                .jsonPath("$.code").isEqualTo("STORAGE_UNAVAILABLE")
                .jsonPath("$.message").isEqualTo("Run storage temporarily unavailable");
    }

    @Test void storageFailureAfterOpeningEmitsOneSafeUnnumberedErrorAndCloses() {
        AgentRun run = mock(AgentRun.class);
        when(run.traceId()).thenReturn("safe-trace");
        when(runs.owned(id, owner)).thenReturn(Mono.just(run));
        when(repository.events(id, 0)).thenReturn(Flux.error(new DataAccessResourceFailureException("private SQL details")));
        StepVerifier.create(service.open(id, 0, owner).flatMapMany(events -> events))
                .assertNext(event -> {
                    assertThat(event.event()).isEqualTo("error");
                    assertThat(event.id()).isNull();
                    assertThat(event.data().path("code").asText()).isEqualTo("STREAM_UNAVAILABLE");
                    assertThat(event.data().path("traceId").asText()).isEqualTo("safe-trace");
                    assertThat(event.data().toString()).doesNotContain("private SQL");
                }).expectComplete().verify(Duration.ofSeconds(2));
    }

    @Test void idleCommentsDoNotAdvanceDurableEventCursor() {
        AgentRun run = mock(AgentRun.class);
        when(runs.owned(id, owner)).thenReturn(Mono.just(run));
        when(repository.events(id, 0)).thenReturn(Flux.empty());
        StepVerifier.create(service.open(id, 0, owner).flatMapMany(events -> events))
                .assertNext(event -> {
                    assertThat(event.comment()).isEqualTo("keep-alive");
                    assertThat(event.id()).isNull();
                    assertThat(event.data()).isNull();
                }).thenCancel().verify(Duration.ofSeconds(2));
    }
}
