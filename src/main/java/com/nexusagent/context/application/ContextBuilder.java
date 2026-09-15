package com.nexusagent.context.application;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.nexusagent.common.context.RequestContext;
import com.nexusagent.common.observation.StageObservation;
import com.nexusagent.common.error.BadRequestException;
import com.nexusagent.embeddings.domain.EmbeddingVector;
import com.nexusagent.context.domain.Citation;
import com.nexusagent.context.domain.ContextAllocation.Status;
import com.nexusagent.context.domain.ContextBuildResult;
import com.nexusagent.context.domain.ContextDebugMetadata;
import com.nexusagent.context.domain.ExpandedCandidateContext;
import com.nexusagent.context.domain.ExpandedParentContext;
import com.nexusagent.context.domain.RerankedCandidate;
import com.nexusagent.context.domain.SelectedChildChunk;
import com.nexusagent.retrieval.application.HybridRetrievalService;
import com.nexusagent.retrieval.domain.HybridRetrievalResult;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

@Service
public class ContextBuilder {
    private final HybridRetrievalService hybridRetrievalService;
    private final Reranker reranker;
    private final ParentContextExpansionService parentContextExpansionService;
    private final CitationFormatter citationFormatter;
    private final ContextProperties contextProperties;
    private final ContextBudgetAllocator allocator = new ContextBudgetAllocator();

    public ContextBuilder(HybridRetrievalService hybridRetrievalService, Reranker reranker,
                          ParentContextExpansionService parentContextExpansionService,
                          CitationFormatter citationFormatter, ContextProperties contextProperties) {
        this.hybridRetrievalService = hybridRetrievalService;
        this.reranker = reranker;
        this.parentContextExpansionService = parentContextExpansionService;
        this.citationFormatter = citationFormatter;
        this.contextProperties = contextProperties;
    }

    public Mono<ContextBuildResult> build(String query, List<UUID> documentIds, Integer topK, Integer budget) {
        return build(query, documentIds, topK, budget, RequestContext.defaults());
    }

    public Mono<ContextBuildResult> build(String query, List<UUID> documentIds, Integer topK,
                                          Integer budget, RequestContext context) {
        return buildInternal(query, documentIds, topK, budget, context, null);
    }

    public Mono<ContextBuildResult> buildWithEmbedding(String query, List<UUID> documentIds, Integer topK,
                                                      Integer budget, RequestContext context, EmbeddingVector embedding) {
        java.util.Objects.requireNonNull(embedding, "Precomputed query embedding is required");
        return buildInternal(query, documentIds, topK, budget, context, embedding);
    }

    private Mono<ContextBuildResult> buildInternal(String query, List<UUID> documentIds, Integer topK,
                                                   Integer requestedBudget, RequestContext context, EmbeddingVector embedding) {
        return Mono.defer(() -> {
            RequestContext access = context == null ? RequestContext.defaults() : context;
            int budget = normalizeBudget(requestedBudget);
            List<UUID> ids = documentIds == null ? List.of() : List.copyOf(documentIds);
            var retrieved = embedding == null ? hybridRetrievalService.retrieve(query, ids, topK, access)
                    : hybridRetrievalService.retrieveWithEmbedding(query, ids, topK, access, embedding);
            return retrieved.flatMap(result -> StageObservation.observe("reranking",
                    () -> Mono.fromSupplier(() -> reranker.rerank(result.query(), result.fusedCandidates())),
                    rows -> Map.of("candidateCount", rows.size(), "reranker", reranker.name()))
                    .flatMap(ranked -> StageObservation.observe("child_selection",
                            () -> parentContextExpansionService.expand(ranked, access).map(rows -> allocator.select(rows, budget)),
                            plan -> Map.of("candidateCount", ranked.size(), "selectedChildCount",
                                    plan.decisions().stream().filter(d -> d.status() == Status.INCLUDED).count(),
                                    "reservedChildChars", plan.reservedChars(), "strategy", ContextBudgetAllocator.VERSION))
                        .flatMap(plan -> StageObservation.observe("parent_expansion",
                                () -> Mono.fromSupplier(() -> allocator.expand(plan)),
                                windows -> Map.of("expandedCount", windows.size(), "usedBudgetChars",
                                        windows.stream().mapToInt(w -> w.end() - w.start()).sum()))
                            .flatMap(windows -> StageObservation.observe("context_building",
                                    () -> Mono.fromSupplier(() -> buildResult(result, ranked, plan, windows)),
                                    built -> Map.of("parentCount", built.expandedParentContexts().size(),
                                            "citationCount", built.citations().size(), "skippedBudgetCount", built.debugMetadata().skippedBudgetCount(),
                                            "formattedChars", built.finalContextText().length()))))));
        });
    }

    private ContextBuildResult buildResult(HybridRetrievalResult retrieval, List<RerankedCandidate> ranked,
                                           ContextBudgetAllocator.Plan plan, List<ContextBudgetAllocator.Window> windows) {
        var children = new ArrayList<SelectedChildChunk>();
        var parents = new ArrayList<ExpandedParentContext>();
        var citations = new ArrayList<Citation>();
        for (var window : windows) {
            var candidate = window.candidate();
            var parent = candidate.parentChunk();
            String text = parent.text().substring(window.start(), window.end());
            boolean trimmed = text.length() < parent.text().length();
            int index = citations.size() + 1;
            citations.add(citationFormatter.citation(index, candidate));
            children.add(selectedChildChunk(candidate, index, trimmed));
            parents.add(new ExpandedParentContext(parent.id(), candidate.document().id(), candidate.document().originalFilename(),
                    parent.chunkIndex(), parent.charStart() + window.start(), parent.charStart() + window.end(), text,
                    trimmed, text.length(), List.of(candidate.childChunk().id())));
        }
        var allocations = allocator.diagnostics(plan, windows);
        int duplicates = (int) allocations.stream().filter(a -> a.status() == Status.DUPLICATE_PARENT).count();
        int skipped = (int) allocations.stream().filter(a -> a.status() == Status.CHILD_EXCEEDS_REMAINING_BUDGET).count();
        return new ContextBuildResult(retrieval.query(), ranked, children, parents, citations,
                citationFormatter.formatContext(parents, citations),
                new ContextDebugMetadata(reranker.name(), retrieval.fusedCandidates().size(), ranked.size(), children.size(), parents.size(),
                        plan.budget(), parents.stream().mapToInt(ExpandedParentContext::includedChars).sum(), duplicates, skipped,
                        ContextBudgetAllocator.VERSION, allocations.size() - duplicates, plan.reservedChars(),
                        (int) parents.stream().filter(ExpandedParentContext::truncated).count(), allocations), retrieval);
    }

    private SelectedChildChunk selectedChildChunk(ExpandedCandidateContext candidate, int index, boolean trimmed) {
        var child = candidate.childChunk();
        var ranked = candidate.rerankedCandidate();
        return new SelectedChildChunk(child.id(), candidate.parentChunk().id(), candidate.document().id(),
                candidate.document().originalFilename(), child.chunkIndex(), child.charStart(), child.charEnd(),
                ranked.candidate().previewText(), ranked.candidate().source(), ranked.rerankedRank(), ranked.rerankScore(),
                "Selected complete child for citation [C%d]; %s%s".formatted(index, ranked.reason(),
                        trimmed ? "; parent fairly expanded within context budget" : ""));
    }

    private int normalizeBudget(Integer requested) {
        if (requested == null) { return contextProperties.getDefaultBudgetChars(); }
        if (requested < 1) { throw new BadRequestException("contextBudgetChars must be greater than 0"); }
        return Math.min(requested, contextProperties.getMaxBudgetChars());
    }
}
