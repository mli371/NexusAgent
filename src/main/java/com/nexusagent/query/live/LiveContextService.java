package com.nexusagent.query.live;

import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexusagent.common.error.OperationException;
import com.nexusagent.common.observation.StageObservation;
import com.nexusagent.context.application.CitationFormatter;
import com.nexusagent.context.application.ContextBuilder;
import com.nexusagent.context.domain.ContextBuildResult;
import com.nexusagent.embeddings.domain.EmbeddingModelInfo;
import com.nexusagent.retrieval.application.RetrievalProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

/** Cache-aside evidence reuse, never answer reuse. PostgreSQL remains the authority. */
final class LiveContextService {
    private static final Logger log = LoggerFactory.getLogger(LiveContextService.class);
    private final ContextBuilder builder;
    private final LiveQueryGuard guard;
    private final LiveContextSnapshotRepository snapshots;
    private final LiveContextCache cache;
    private final EmbeddingModelInfo model;
    private final RetrievalProperties retrieval;
    private final ObjectMapper mapper;

    LiveContextService(ContextBuilder builder, LiveQueryGuard guard, LiveContextSnapshotRepository snapshots,
                       LiveContextCache cache, EmbeddingModelInfo model, RetrievalProperties retrieval, ObjectMapper mapper) {
        this.builder = builder;
        this.guard = guard;
        this.snapshots = snapshots;
        this.cache = cache;
        this.model = model;
        this.retrieval = retrieval;
        this.mapper = mapper;
    }

    String mode() { return cache.enabled() ? "versioned_context" : "bypassed"; }

    Mono<Result> load(LiveQueryInput input, QueryTrace trace) {
        if (!cache.enabled()) {
            trace.skipped("cache_lookup", "disabled");
            return fresh(input).map(context -> new Result(context, "bypassed", null));
        }
        return snapshots.read(input).flatMap(versions -> {
            String key = LiveContextCacheKey.create(input, versions, model, retrieval, mapper);
            return StageObservation.observe("cache_lookup", () -> cache.get(key)
                            .flatMap(lookup -> validateHit(input, lookup)),
                            lookup -> Map.of("cacheStatus", lookup.context() == null ? "miss" : "hit", "reason", lookup.reason()))
                    .flatMap(lookup -> {
                        boolean hit = lookup.context() != null;
                        log.info("live_context_cache_lookup traceId={} status={} reason={}", input.traceId(), hit ? "hit" : "miss", lookup.reason());
                        if (hit) {
                            for (String stage : List.of("query_embedding", "vector_search", "full_text_search", "rrf_fusion",
                                    "reranking", "parent_expansion", "context_building")) {
                                trace.skipped(stage, "cache_reuse");
                            }
                            return snapshots.assertCurrent(input, versions)
                                    .thenReturn(new Result(lookup.context(), "hit", versions));
                        }
                        return fresh(input).flatMap(context -> snapshots.assertCurrent(input, versions)
                                .then(cache.put(key, context)).thenReturn(new Result(context, "miss", versions)));
                    });
        });
    }

    private Mono<LiveContextCache.Lookup> validateHit(LiveQueryInput input, LiveContextCache.Lookup lookup) {
        if (lookup.context() == null) { return Mono.just(lookup); }
        return Mono.defer(() -> {
            var context = RedisLiveContextCache.withQuery(lookup.context(), input.question());
            if (context.finalContextText().isBlank() || context.citations().isEmpty()
                    || context.citations().size() > input.topK() || context.rerankedCandidates().size() > input.topK()
                    || context.expandedParentContexts().size() != context.citations().size()
                    || context.expandedParentContexts().stream().mapToLong(p -> p.text().length()).sum() > input.budget()
                    || !new CitationFormatter().formatContext(context.expandedParentContexts(), context.citations()).equals(context.finalContextText())) {
                return Mono.just(new LiveContextCache.Lookup(null, "invalid_evidence"));
            }
            for (int index = 0; index < context.citations().size(); index++) {
                var citation = context.citations().get(index);
                var parent = context.expandedParentContexts().get(index);
                if (!citation.citationMarker().equals("[C" + (index + 1) + "]")
                        || !citation.documentId().equals(parent.documentId()) || !citation.parentChunkId().equals(parent.parentChunkId())
                        || !citation.originalFilename().equals(parent.originalFilename())
                        || !parent.childChunkIds().contains(citation.childChunkId())
                        || context.selectedChildChunks().stream().noneMatch(child -> child.childChunkId().equals(citation.childChunkId())
                            && child.parentChunkId().equals(citation.parentChunkId()) && child.documentId().equals(citation.documentId())
                            && child.chunkIndex() == citation.chunkIndex() && child.charStart() == citation.charStart()
                            && child.charEnd() == citation.charEnd() && child.originalFilename().equals(citation.originalFilename()))) {
                    return Mono.just(new LiveContextCache.Lookup(null, "invalid_evidence"));
                }
            }
            return guard.evidence(input, context).thenReturn(new LiveContextCache.Lookup(context, "validated"));
        }).onErrorResume(error -> {
            if (error instanceof OperationException operation) {
                if ("DOCUMENT_CHANGED".equals(operation.code())) {
                    return Mono.just(new LiveContextCache.Lookup(null, "stale_evidence"));
                }
                return Mono.error(error); // Permission/readiness errors are not Redis failures.
            }
            if (error instanceof NullPointerException || error instanceof IndexOutOfBoundsException || error instanceof IllegalArgumentException) {
                return Mono.just(new LiveContextCache.Lookup(null, "invalid_evidence"));
            }
            return Mono.error(error);
        });
    }

    private Mono<ContextBuildResult> fresh(LiveQueryInput input) {
        return guard.access(input).then(Mono.defer(() -> builder.build(input.question(), input.documentIds(), input.topK(), input.budget(), input.context())))
                .switchIfEmpty(Mono.error(LiveQueryGuard.changed()))
                .flatMap(context -> guard.evidence(input, context).thenReturn(context));
    }

    Mono<Void> assertCurrent(LiveQueryInput input, Result result) {
        return result.versions() == null ? Mono.empty() : snapshots.assertCurrent(input, result.versions());
    }

    record Result(ContextBuildResult context, String cacheStatus, List<LiveContextSnapshotRepository.DocumentVersion> versions) { }
}
