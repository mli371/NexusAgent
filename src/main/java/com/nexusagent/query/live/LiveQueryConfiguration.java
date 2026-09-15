package com.nexusagent.query.live;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexusagent.chunking.repository.ChunkRepository;
import com.nexusagent.context.application.ContextBuilder;
import com.nexusagent.context.application.ContextProperties;
import com.nexusagent.documents.repository.DocumentRepository;
import com.nexusagent.embeddings.application.EmbeddingService;
import com.nexusagent.embeddings.repository.ChildChunkEmbeddingRepository;
import com.nexusagent.enterprise.audit.AuditService;
import com.nexusagent.model.OpenAiHttpClient;
import com.nexusagent.model.OpenAiProperties;
import com.nexusagent.query.application.OpenAiAnswerGenerator;
import com.nexusagent.query.application.QueryProperties;
import com.nexusagent.query.application.SessionStateService;
import com.nexusagent.query.application.ToolOutputStore;
import com.nexusagent.query.redis.NexusRedisProperties;
import com.nexusagent.retrieval.application.RetrievalProperties;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class LiveQueryConfiguration {
    @Bean
    InitializingBean validateAnswerProvider(QueryProperties properties) {
        return () -> {
            if (!java.util.Set.of("local", "openai").contains(properties.getAnswerProvider())) {
                throw new IllegalArgumentException("nexus.query.answer-provider must be local or openai");
            }
            for (var timeout : java.util.List.of(properties.getLiveContextTimeout(), properties.getStateTimeout())) {
                if (timeout.isNegative() || timeout.isZero() || timeout.compareTo(java.time.Duration.ofMinutes(5)) > 0) {
                    throw new IllegalArgumentException("Live query timeouts must be positive and no more than 5 minutes");
                }
            }
        };
    }

    @Configuration
    @ConditionalOnProperty(name = "nexus.query.answer-provider", havingValue = "openai")
    static class Enabled {
        @Bean
        OpenAiAnswerGenerator liveAnswerGenerator(ObjectProvider<OpenAiHttpClient> clients, OpenAiProperties properties,
                                                   ObjectMapper mapper, EmbeddingService embeddings) {
            if (!"openai".equals(embeddings.modelInfo().provider())) {
                throw new IllegalArgumentException("Live answers require nexus.embeddings.provider=openai");
            }
            properties.validate();
            return new OpenAiAnswerGenerator(clients.getObject(), properties, mapper);
        }

        @Bean
        LiveQueryGuard liveQueryGuard(DocumentRepository documents, ChildChunkEmbeddingRepository embeddings,
                                      EmbeddingService model, ChunkRepository chunks, QueryLibraryRepository library) {
            return new LiveQueryGuard(documents, embeddings, model, chunks, library);
        }

        @Bean
        LiveQueryService liveQueryService(ContextBuilder contexts, OpenAiAnswerGenerator answers, LiveQueryGuard guard,
                                          SessionStateService sessions, ToolOutputStore tools, AuditService audit,
                                          QueryProperties properties, OpenAiProperties openAi, RetrievalProperties retrieval,
                                          ContextProperties contextProperties, ObjectMapper mapper,
                                          LiveContextSnapshotRepository snapshots, EmbeddingService embeddings,
                                          NexusRedisProperties redisProperties,
                                          ObjectProvider<org.springframework.data.redis.core.ReactiveStringRedisTemplate> redis) {
            if (retrieval.getMaxTopK() < 1 || retrieval.getMaxTopK() > 50
                    || retrieval.getDefaultTopK() < 1 || retrieval.getDefaultTopK() > retrieval.getMaxTopK()
                    || contextProperties.getMaxBudgetChars() < 1 || contextProperties.getMaxBudgetChars() > 12000
                    || contextProperties.getDefaultBudgetChars() < 1
                    || contextProperties.getDefaultBudgetChars() > contextProperties.getMaxBudgetChars()) {
                throw new IllegalArgumentException("Live query defaults must fit maxima: topK <= 50, parent budget <= 12000 chars");
            }
            LiveContextCache cache = properties.isLiveCacheEnabled() && redisProperties.isEnabled()
                    ? new RedisLiveContextCache(redis.getObject(), mapper, properties) : LiveContextCache.disabled();
            var cachedContexts = new LiveContextService(contexts, guard, snapshots, cache, embeddings.modelInfo(), retrieval, mapper);
            return new LiveQueryService(cachedContexts, answers, guard, sessions, tools, audit, properties,
                    openAi, retrieval, contextProperties, mapper);
        }
    }
}
