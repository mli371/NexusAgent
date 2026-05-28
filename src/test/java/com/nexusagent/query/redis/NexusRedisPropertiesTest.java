package com.nexusagent.query.redis;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;

import org.junit.jupiter.api.Test;

class NexusRedisPropertiesTest {

    @Test
    void rejectsNonPositiveTtlsAndLimits() {
        NexusRedisProperties properties = new NexusRedisProperties();

        assertThatThrownBy(() -> properties.setRetrievalCacheTtl(Duration.ZERO))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("positive duration");
        assertThatThrownBy(() -> properties.setToolOutputTtl(Duration.ofSeconds(-1)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("positive duration");
        assertThatThrownBy(() -> properties.setMaxCacheEntryBytes(0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("greater than 0");
    }
}
