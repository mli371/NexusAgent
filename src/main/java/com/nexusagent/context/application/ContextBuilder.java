package com.nexusagent.context.application;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import com.nexusagent.common.context.RequestContext;
import com.nexusagent.common.error.BadRequestException;
import com.nexusagent.context.domain.Citation;
import com.nexusagent.context.domain.ContextBuildResult;
import com.nexusagent.context.domain.ContextDebugMetadata;
import com.nexusagent.context.domain.ExpandedCandidateContext;
import com.nexusagent.context.domain.ExpandedParentContext;
import com.nexusagent.context.domain.RerankedCandidate;
import com.nexusagent.context.domain.SelectedChildChunk;
import com.nexusagent.retrieval.application.HybridRetrievalService;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

@Service
public class ContextBuilder {

    private final HybridRetrievalService hybridRetrievalService;
    private final Reranker reranker;
    private final ParentContextExpansionService parentContextExpansionService;
    private final CitationFormatter citationFormatter;
    private final ContextProperties contextProperties;

    public ContextBuilder(
            HybridRetrievalService hybridRetrievalService,
            Reranker reranker,
            ParentContextExpansionService parentContextExpansionService,
            CitationFormatter citationFormatter,
            ContextProperties contextProperties
    ) {
        this.hybridRetrievalService = hybridRetrievalService;
        this.reranker = reranker;
        this.parentContextExpansionService = parentContextExpansionService;
        this.citationFormatter = citationFormatter;
        this.contextProperties = contextProperties;
    }

    public Mono<ContextBuildResult> build(
            String query,
            List<UUID> documentIds,
            Integer topK,
            Integer requestedContextBudgetChars
    ) {
        return build(query, documentIds, topK, requestedContextBudgetChars, RequestContext.defaults());
    }

    public Mono<ContextBuildResult> build(
            String query,
            List<UUID> documentIds,
            Integer topK,
            Integer requestedContextBudgetChars,
            RequestContext context
    ) {
        return Mono.defer(() -> {
            RequestContext effectiveContext = context == null ? RequestContext.defaults() : context;
            int contextBudgetChars = normalizeBudget(requestedContextBudgetChars);
            List<UUID> normalizedDocumentIds = documentIds == null ? List.of() : List.copyOf(documentIds);
            return hybridRetrievalService.retrieve(query, normalizedDocumentIds, topK, effectiveContext)
                    .flatMap(retrievalResult -> {
                        List<RerankedCandidate> rerankedCandidates = reranker.rerank(
                                retrievalResult.query(),
                                retrievalResult.fusedCandidates()
                        );
                        return parentContextExpansionService.expand(rerankedCandidates, effectiveContext)
                                .map(expandedCandidates -> buildResult(
                                        retrievalResult.query(),
                                        retrievalResult.fusedCandidates().size(),
                                        retrievalResult,
                                        rerankedCandidates,
                                        expandedCandidates,
                                        contextBudgetChars
                                ));
                    });
        });
    }

    private ContextBuildResult buildResult(
            String query,
            int fusedCandidateCount,
            com.nexusagent.retrieval.domain.HybridRetrievalResult retrievalResult,
            List<RerankedCandidate> rerankedCandidates,
            List<ExpandedCandidateContext> expandedCandidates,
            int contextBudgetChars
    ) {
        Selection selection = selectParentContexts(expandedCandidates, contextBudgetChars);
        String finalContextText = citationFormatter.formatContext(
                selection.parentContexts(),
                selection.citations()
        );
        return new ContextBuildResult(
                query,
                rerankedCandidates,
                selection.selectedChildChunks(),
                selection.parentContexts(),
                selection.citations(),
                finalContextText,
                new ContextDebugMetadata(
                        reranker.name(),
                        fusedCandidateCount,
                        rerankedCandidates.size(),
                        selection.selectedChildChunks().size(),
                        selection.parentContexts().size(),
                        contextBudgetChars,
                        selection.usedBudgetChars(),
                        selection.skippedDuplicateParentCount(),
                        selection.skippedBudgetCount()
                ),
                retrievalResult
        );
    }

    private Selection selectParentContexts(
            List<ExpandedCandidateContext> expandedCandidates,
            int contextBudgetChars
    ) {
        List<SelectedChildChunk> selectedChildChunks = new ArrayList<>();
        List<ExpandedParentContext> parentContexts = new ArrayList<>();
        List<Citation> citations = new ArrayList<>();
        Set<UUID> selectedParentIds = new HashSet<>();
        int usedBudgetChars = 0;
        int skippedDuplicateParentCount = 0;
        int skippedBudgetCount = 0;

        for (ExpandedCandidateContext expandedCandidate : expandedCandidates) {
            if (selectedParentIds.contains(expandedCandidate.parentChunk().id())) {
                skippedDuplicateParentCount++;
                continue;
            }

            int remainingBudget = contextBudgetChars - usedBudgetChars;
            if (remainingBudget <= 0) {
                skippedBudgetCount++;
                continue;
            }

            ParentTextSelection parentTextSelection = selectParentText(expandedCandidate, remainingBudget);

            selectedParentIds.add(expandedCandidate.parentChunk().id());
            usedBudgetChars += parentTextSelection.text().length();

            int citationIndex = citations.size() + 1;
            citations.add(citationFormatter.citation(citationIndex, expandedCandidate));
            selectedChildChunks.add(selectedChildChunk(expandedCandidate, citationIndex, parentTextSelection.truncated()));
            parentContexts.add(new ExpandedParentContext(
                    expandedCandidate.parentChunk().id(),
                    expandedCandidate.document().id(),
                    expandedCandidate.document().originalFilename(),
                    expandedCandidate.parentChunk().chunkIndex(),
                    parentTextSelection.charStart(),
                    parentTextSelection.charEnd(),
                    parentTextSelection.text(),
                    parentTextSelection.truncated(),
                    parentTextSelection.text().length(),
                    List.of(expandedCandidate.childChunk().id())
            ));
        }

        return new Selection(
                selectedChildChunks,
                parentContexts,
                citations,
                usedBudgetChars,
                skippedDuplicateParentCount,
                skippedBudgetCount
        );
    }

    private ParentTextSelection selectParentText(ExpandedCandidateContext expandedCandidate, int budgetChars) {
        String parentText = expandedCandidate.parentChunk().text();
        if (parentText.length() <= budgetChars) {
            return new ParentTextSelection(
                    parentText,
                    false,
                    expandedCandidate.parentChunk().charStart(),
                    expandedCandidate.parentChunk().charEnd()
            );
        }

        int parentLength = parentText.length();
        int childRelativeStart = clamp(
                expandedCandidate.childChunk().charStart() - expandedCandidate.parentChunk().charStart(),
                0,
                parentLength
        );
        int childRelativeEnd = clamp(
                expandedCandidate.childChunk().charEnd() - expandedCandidate.parentChunk().charStart(),
                childRelativeStart,
                parentLength
        );
        int snippetStart = childCenteredStart(parentLength, childRelativeStart, childRelativeEnd, budgetChars);
        int snippetEnd = Math.min(snippetStart + budgetChars, parentLength);

        return new ParentTextSelection(
                parentText.substring(snippetStart, snippetEnd),
                true,
                expandedCandidate.parentChunk().charStart() + snippetStart,
                expandedCandidate.parentChunk().charStart() + snippetEnd
        );
    }

    private int childCenteredStart(int parentLength, int childStart, int childEnd, int budgetChars) {
        int maxStart = Math.max(parentLength - budgetChars, 0);
        int childLength = Math.max(childEnd - childStart, 0);
        int start;

        if (childLength >= budgetChars) {
            start = childStart;
        } else {
            int childCenter = childStart + (childLength / 2);
            start = childCenter - (budgetChars / 2);
            if (start > childStart) {
                start = childStart;
            }
            if (start + budgetChars < childEnd) {
                start = childEnd - budgetChars;
            }
        }

        return clamp(start, 0, maxStart);
    }

    private int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(value, max));
    }

    private SelectedChildChunk selectedChildChunk(
            ExpandedCandidateContext expandedCandidate,
            int citationIndex,
            boolean parentWasTruncated
    ) {
        String reason = "Selected for citation [C%d]; %s%s"
                .formatted(
                        citationIndex,
                        expandedCandidate.rerankedCandidate().reason(),
                        parentWasTruncated ? "; parent trimmed to fit context budget" : ""
                );
        return new SelectedChildChunk(
                expandedCandidate.childChunk().id(),
                expandedCandidate.parentChunk().id(),
                expandedCandidate.document().id(),
                expandedCandidate.document().originalFilename(),
                expandedCandidate.childChunk().chunkIndex(),
                expandedCandidate.childChunk().charStart(),
                expandedCandidate.childChunk().charEnd(),
                expandedCandidate.rerankedCandidate().candidate().previewText(),
                expandedCandidate.rerankedCandidate().candidate().source(),
                expandedCandidate.rerankedCandidate().rerankedRank(),
                expandedCandidate.rerankedCandidate().rerankScore(),
                reason
        );
    }

    private int normalizeBudget(Integer requestedContextBudgetChars) {
        if (requestedContextBudgetChars == null) {
            return contextProperties.getDefaultBudgetChars();
        }
        if (requestedContextBudgetChars < 1) {
            throw new BadRequestException("contextBudgetChars must be greater than 0");
        }
        return Math.min(requestedContextBudgetChars, contextProperties.getMaxBudgetChars());
    }

    private record Selection(
            List<SelectedChildChunk> selectedChildChunks,
            List<ExpandedParentContext> parentContexts,
            List<Citation> citations,
            int usedBudgetChars,
            int skippedDuplicateParentCount,
            int skippedBudgetCount
    ) {
    }

    private record ParentTextSelection(
            String text,
            boolean truncated,
            int charStart,
            int charEnd
    ) {
    }
}
