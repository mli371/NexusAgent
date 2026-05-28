package com.nexusagent.query.redis;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import com.nexusagent.agent.api.AgentQueryRequest;
import com.nexusagent.agent.application.AgentOrchestrator;
import com.nexusagent.agent.domain.AgentWorkflowStatus;
import com.nexusagent.query.application.InMemorySessionStateService;
import com.nexusagent.query.application.NoOpRetrievalCacheService;
import com.nexusagent.query.application.NoOpToolOutputStore;
import com.nexusagent.query.application.RetrievalCacheService;
import com.nexusagent.query.application.SessionStateService;
import com.nexusagent.query.application.ToolOutputStore;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import reactor.test.StepVerifier;

@SpringBootTest(properties = {
        "spring.flyway.enabled=false",
        "nexus.redis.enabled=false"
})
class RedisDisabledFallbackContextTest {

    @Autowired
    SessionStateService sessionStateService;

    @Autowired
    RetrievalCacheService retrievalCacheService;

    @Autowired
    ToolOutputStore toolOutputStore;

    @Autowired
    AgentOrchestrator agentOrchestrator;

    @Test
    void redisDisabledUsesInMemoryAndNoOpImplementations() {
        assertThat(sessionStateService).isInstanceOf(InMemorySessionStateService.class);
        assertThat(retrievalCacheService).isInstanceOf(NoOpRetrievalCacheService.class);
        assertThat(toolOutputStore).isInstanceOf(NoOpToolOutputStore.class);
    }

    @Test
    void agentWorkflowStillWorksWhenRedisIsDisabled() {
        StepVerifier.create(agentOrchestrator.query(new AgentQueryRequest("s1", "hello", List.of(), 5, 1000, true)))
                .assertNext(response -> {
                    assertThat(response.workflowStatus()).isEqualTo(AgentWorkflowStatus.COMPLETED);
                    assertThat(response.executionResult().retrievalUsed()).isFalse();
                })
                .verifyComplete();
    }
}
