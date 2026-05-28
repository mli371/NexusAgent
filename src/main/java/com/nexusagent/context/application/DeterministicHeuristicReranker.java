package com.nexusagent.context.application;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

import com.nexusagent.context.domain.RerankScoreBreakdown;
import com.nexusagent.context.domain.RerankedCandidate;
import com.nexusagent.retrieval.domain.FusedRetrievalCandidate;
import com.nexusagent.retrieval.domain.RetrievalSource;
import org.springframework.stereotype.Service;

@Service
public class DeterministicHeuristicReranker implements Reranker {

    private static final Pattern TOKEN_SPLIT = Pattern.compile("[^\\p{Alnum}]+");
    private static final double RRF_WEIGHT = 100.0d;
    private static final double KEYWORD_OVERLAP_WEIGHT = 0.40d;
    private static final double BOTH_SOURCE_BOOST = 0.20d;
    private static final double SINGLE_SOURCE_BOOST = 0.05d;
    private static final double REPEATED_PARENT_PENALTY = 0.30d;
    private static final double REPEATED_DOCUMENT_PENALTY = 0.05d;

    @Override
    public String name() {
        return "deterministic-heuristic";
    }

    @Override
    public List<RerankedCandidate> rerank(String query, List<FusedRetrievalCandidate> candidates) {
        Set<String> queryTokens = tokenize(query);
        List<CandidateScore> remaining = new ArrayList<>();
        for (int index = 0; index < candidates.size(); index++) {
            remaining.add(score(candidates.get(index), index + 1, queryTokens));
        }

        List<RerankedCandidate> ranked = new ArrayList<>();
        Set<UUID> selectedParents = new HashSet<>();
        Map<UUID, Integer> selectedDocumentCounts = new HashMap<>();

        while (!remaining.isEmpty()) {
            CandidateScore best = remaining.stream()
                    .map(candidate -> candidate.withDiversityPenalty(
                            selectedParents.contains(candidate.candidate().parentChunkId()),
                            selectedDocumentCounts.getOrDefault(candidate.candidate().documentId(), 0)
                    ))
                    .max(Comparator.comparingDouble(CandidateScore::rerankScore)
                            .thenComparing(candidate -> -candidate.originalRank()))
                    .orElseThrow();

            remaining.removeIf(candidate -> candidate.candidate().childChunkId()
                    .equals(best.candidate().childChunkId()));
            selectedParents.add(best.candidate().parentChunkId());
            selectedDocumentCounts.merge(best.candidate().documentId(), 1, Integer::sum);

            int rerankedRank = ranked.size() + 1;
            ranked.add(new RerankedCandidate(
                    best.candidate(),
                    best.originalRank(),
                    rerankedRank,
                    best.rerankScore(),
                    new RerankScoreBreakdown(
                            best.rrfComponent(),
                            best.keywordOverlapScore(),
                            best.sourceBoost(),
                            best.diversityPenalty()
                    ),
                    selectionReason(best, rerankedRank)
            ));
        }

        return ranked;
    }

    private CandidateScore score(
            FusedRetrievalCandidate candidate,
            int originalRank,
            Set<String> queryTokens
    ) {
        Set<String> previewTokens = tokenize(candidate.previewText());
        int overlapCount = 0;
        for (String token : queryTokens) {
            if (previewTokens.contains(token)) {
                overlapCount++;
            }
        }

        double overlapRatio = queryTokens.isEmpty() ? 0.0d : (double) overlapCount / queryTokens.size();
        double keywordOverlapScore = overlapRatio * KEYWORD_OVERLAP_WEIGHT;
        double sourceBoost = candidate.source() == RetrievalSource.BOTH ? BOTH_SOURCE_BOOST : SINGLE_SOURCE_BOOST;
        double rrfComponent = candidate.rrfScore() * RRF_WEIGHT;

        return new CandidateScore(
                candidate,
                originalRank,
                rrfComponent,
                keywordOverlapScore,
                sourceBoost,
                0.0d,
                rrfComponent + keywordOverlapScore + sourceBoost
        );
    }

    private Set<String> tokenize(String text) {
        if (text == null || text.isBlank()) {
            return Set.of();
        }

        Set<String> tokens = new HashSet<>();
        for (String token : TOKEN_SPLIT.split(text.toLowerCase())) {
            if (!token.isBlank()) {
                tokens.add(token);
            }
        }
        return tokens;
    }

    private String selectionReason(CandidateScore score, int rerankedRank) {
        return "rank=%d rrf=%.4f keyword=%.4f source=%.4f diversityPenalty=%.4f"
                .formatted(
                        rerankedRank,
                        score.rrfComponent(),
                        score.keywordOverlapScore(),
                        score.sourceBoost(),
                        score.diversityPenalty()
                );
    }

    private record CandidateScore(
            FusedRetrievalCandidate candidate,
            int originalRank,
            double rrfComponent,
            double keywordOverlapScore,
            double sourceBoost,
            double diversityPenalty,
            double rerankScore
    ) {

        private CandidateScore withDiversityPenalty(boolean parentAlreadySelected, int selectedDocumentCount) {
            double penalty = 0.0d;
            if (parentAlreadySelected) {
                penalty += REPEATED_PARENT_PENALTY;
            }
            penalty += selectedDocumentCount * REPEATED_DOCUMENT_PENALTY;
            return new CandidateScore(
                    candidate,
                    originalRank,
                    rrfComponent,
                    keywordOverlapScore,
                    sourceBoost,
                    penalty,
                    rrfComponent + keywordOverlapScore + sourceBoost - penalty
            );
        }
    }
}
