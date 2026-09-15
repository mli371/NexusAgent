package com.nexusagent.query.live;

import java.util.List;
import java.util.Map;
import java.util.LinkedHashMap;
import java.time.Instant;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexusagent.common.error.OperationException;
import com.nexusagent.common.observation.StageObservation;
import com.nexusagent.context.application.CitationFormatter;
import com.nexusagent.context.application.ContextBuilder;
import com.nexusagent.context.domain.ContextBuildResult;
import com.nexusagent.embeddings.domain.EmbeddingModelInfo;
import com.nexusagent.embeddings.domain.EmbeddingVector;
import com.nexusagent.embeddings.application.EmbeddingService;
import com.nexusagent.query.application.QueryProperties;
import com.nexusagent.retrieval.application.RetrievalProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Flux;

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
    private final EmbeddingService embeddings;
    private final SemanticContextCache semantic;
    private final QueryProperties properties;
    private final SemanticReusePolicy policy = new SemanticReusePolicy();

    LiveContextService(ContextBuilder builder, LiveQueryGuard guard, LiveContextSnapshotRepository snapshots,
                       LiveContextCache cache, EmbeddingModelInfo model, RetrievalProperties retrieval, ObjectMapper mapper) {
        this(builder, guard, snapshots, cache, model, retrieval, mapper, null, SemanticContextCache.disabled(), new QueryProperties());
    }

    LiveContextService(ContextBuilder builder, LiveQueryGuard guard, LiveContextSnapshotRepository snapshots,
                       LiveContextCache cache, EmbeddingModelInfo model, RetrievalProperties retrieval, ObjectMapper mapper,
                       EmbeddingService embeddings, SemanticContextCache semantic, QueryProperties properties) {
        this.builder = builder;
        this.guard = guard;
        this.snapshots = snapshots;
        this.cache = cache;
        this.model = model;
        this.retrieval = retrieval;
        this.mapper = mapper;
        this.embeddings = embeddings;
        this.semantic = semantic;
        this.properties = properties;
    }

    String mode() { return !cache.enabled() ? "bypassed" : semantic.enabled() ? "versioned_semantic_context" : "versioned_context"; }

    Mono<Result> load(LiveQueryInput input, QueryTrace trace) {
        if (!cache.enabled()) {
            trace.skipped("cache_lookup", "disabled");
            trace.skipped("semantic_cache_lookup", "disabled");
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
                            trace.skipped("semantic_cache_lookup", "exact_cache_hit");
                            trace.skipped("query_embedding", "cache_reuse");
                            skipRetrieval(trace);
                            return snapshots.assertCurrent(input, versions)
                                    .thenReturn(new Result(lookup.context(), "hit", versions));
                        }
                        return afterExactMiss(input, versions, key, trace);
                    });
        });
    }

    private Mono<Result> afterExactMiss(LiveQueryInput input, List<LiveContextSnapshotRepository.DocumentVersion> versions,
                                       String key, QueryTrace trace) {
        var features = policy.features(input.question());
        if (!semantic.enabled() || features.isEmpty()) {
            trace.skipped("semantic_cache_lookup", semantic.enabled() ? "ineligible_query" : "disabled");
            return buildAndStore(input, versions, key, null, null);
        }
        String scope = LiveContextCacheKey.semanticScope(input, versions, model, retrieval, mapper);
        return StageObservation.observe("query_embedding", () -> embeddings.embed(input.question())
                        .switchIfEmpty(Mono.error(OperationException.invalidModelResponse())),
                        ignored -> Map.of("model", model.modelName()))
                .flatMap(vector -> StageObservation.observe("semantic_cache_lookup",
                                () -> semanticLookup(input, scope, features.get(), vector), this::semanticSummary)
                        .flatMap(selection -> {
                            log.info("semantic_context_cache_lookup traceId={} status={} reason={}", input.traceId(),
                                    selection.context() == null ? "miss" : "semantic_hit", selection.reason());
                            if (selection.context() == null) {
                                return buildAndStore(input, versions, key, vector, features.get());
                            }
                            skipRetrieval(trace);
                            return snapshots.assertCurrent(input, versions)
                                    .thenReturn(new Result(selection.context(), "semantic_hit", versions));
                        }));
    }

    private Mono<Selection> semanticLookup(LiveQueryInput input, String scope, SemanticReusePolicy.Features features, EmbeddingVector vector) {
        return semantic.read(scope, model.dimension()).flatMap(read -> {
            var ranking = policy.rank(features, vector.values(), read.sources(), properties.getSemanticCacheMinSimilarity());
            return Flux.fromIterable(ranking.matches()).concatMap(match -> cache.get(match.source().cacheKey())
                            .filter(value -> value.context() != null && match.source().expiresAt().isAfter(Instant.now())
                                    && match.source().fingerprint().equals(value.fingerprint()) && value.expiresAt() != null
                                    && !match.source().expiresAt().isAfter(value.expiresAt()))
                            .flatMap(value -> validateHit(input, value)).filter(value -> value.context() != null)
                            .map(value -> new Selection(value.context(), "validated", match.similarity(), read.sources().size())))
                    .next().defaultIfEmpty(new Selection(null, read.sources().isEmpty() ? read.reason()
                            : ranking.matches().isEmpty() ? ranking.reason() : "invalid_source_evidence",
                            ranking.bestSimilarity(), read.sources().size()));
        });
    }

    private Map<String, Object> semanticSummary(Selection selection) {
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("cacheStatus", selection.context() == null ? "miss" : "semantic_hit");
        summary.put("reason", selection.reason());
        summary.put("threshold", properties.getSemanticCacheMinSimilarity());
        summary.put("candidateCount", selection.candidateCount());
        if (selection.similarity() != null) { summary.put("similarity", selection.similarity()); }
        if (selection.context() != null) { summary.put("dataOrigin", "cached_source_query"); }
        return Map.copyOf(summary);
    }

    private Mono<Result> buildAndStore(LiveQueryInput input, List<LiveContextSnapshotRepository.DocumentVersion> versions,
                                      String key, EmbeddingVector vector, SemanticReusePolicy.Features features) {
        return fresh(input, vector).flatMap(context -> snapshots.assertCurrent(input, versions)
                .then(Mono.defer(() -> {
                    if (vector == null || features == null) { return cache.put(key, context); }
                    String scope = LiveContextCacheKey.semanticScope(input, versions, model, retrieval, mapper);
                    return cache.putWithReceipt(key, context).flatMap(stored -> semantic.put(new SemanticContextCache.Source(
                            1, scope, LiveContextCacheKey.hash(input.question()), vector.values(), features,
                            stored.cacheKey(), stored.fingerprint(), Instant.now(), stored.expiresAt())));
                })).thenReturn(new Result(context, "miss", versions)));
    }

    private void skipRetrieval(QueryTrace trace) {
        for (String stage : List.of("vector_search", "full_text_search", "rrf_fusion", "reranking", "child_selection", "parent_expansion", "context_building")) {
            trace.skipped(stage, "cache_reuse");
        }
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
        return fresh(input, null);
    }

    private Mono<ContextBuildResult> fresh(LiveQueryInput input, EmbeddingVector vector) {
        return guard.access(input).then(Mono.defer(() -> vector == null
                        ? builder.build(input.question(), input.documentIds(), input.topK(), input.budget(), input.context())
                        : builder.buildWithEmbedding(input.question(), input.documentIds(), input.topK(), input.budget(), input.context(), vector)))
                .switchIfEmpty(Mono.error(LiveQueryGuard.changed()))
                .flatMap(context -> guard.evidence(input, context).thenReturn(context));
    }

    Mono<Void> assertCurrent(LiveQueryInput input, Result result) {
        return result.versions() == null ? Mono.empty() : snapshots.assertCurrent(input, result.versions());
    }

    record Result(ContextBuildResult context, String cacheStatus, List<LiveContextSnapshotRepository.DocumentVersion> versions) { }
    private record Selection(ContextBuildResult context, String reason, Double similarity, int candidateCount) { }
}
