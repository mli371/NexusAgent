package com.nexusagent.query.api;

import com.nexusagent.embeddings.application.EmbeddingService;
import com.nexusagent.embeddings.domain.EmbeddingModelInfo;
import com.nexusagent.query.application.AnswerGenerator;
import com.nexusagent.query.live.LiveQueryService;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class QueryCapabilitiesController {
    private final EmbeddingService embeddings;
    private final AnswerGenerator answers;
    private final LiveQueryService live;

    public QueryCapabilitiesController(EmbeddingService embeddings, AnswerGenerator answers) {
        this.embeddings = embeddings;
        this.answers = answers;
        this.live = null;
    }

    @Autowired
    public QueryCapabilitiesController(EmbeddingService embeddings, AnswerGenerator answers, ObjectProvider<LiveQueryService> live) {
        this.embeddings = embeddings;
        this.answers = answers;
        this.live = live.getIfAvailable();
    }

    @GetMapping("/api/v1/query/capabilities")
    public Capabilities capabilities() {
        return new Capabilities(live == null ? answers.name() : "openai-responses", embeddings.modelInfo(), true, live != null,
                live == null ? "Offline query mode; set NEXUS_ANSWER_PROVIDER=openai and configure real embeddings"
                        : "Live pipeline configured; document readiness is checked per request. Credentials/account availability are not probed.",
                live == null ? "legacy_only" : live.cacheMode(), 2000, 10, live == null ? null : live.modelName(),
                java.util.List.of("library", "documents"), com.nexusagent.query.live.QueryLibraryRepository.MAX_DOCUMENTS);
    }

    public record Capabilities(String activeAnswerGenerator, EmbeddingModelInfo embedding,
                               boolean openAiAnswerAdapterAvailable, boolean liveQueryReady, String reason,
                               String retrievalCacheMode, int liveQuestionMaxChars, int liveDocumentLimit, String answerModel,
                               java.util.List<String> queryScopes, int maxLibraryDocuments) { }
}
