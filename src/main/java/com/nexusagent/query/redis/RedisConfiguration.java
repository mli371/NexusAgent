package com.nexusagent.query.redis;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@ConditionalOnProperty(prefix = "nexus.redis", name = "enabled", havingValue = "true")
public class RedisConfiguration {

    @Bean
    RedisKeyFactory redisKeyFactory() {
        return new RedisKeyFactory();
    }
}
