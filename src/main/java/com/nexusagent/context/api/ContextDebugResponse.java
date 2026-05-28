package com.nexusagent.context.api;

import java.util.List;
import java.util.UUID;

import com.nexusagent.context.domain.Citation;
import com.nexusagent.context.domain.ContextBuildResult;
import com.nexusagent.context.domain.ContextDebugMetadata;
import com.nexusagent.context.domain.ExpandedParentContext;
import com.nexusagent.context.domain.RerankScoreBreakdown;
import com.nexusagent.context.domain.RerankedCandidate;
import com.nexusagent.context.domain.SelectedChildChunk;
import com.nexusagent.retrieval.domain.RetrievalSource;

public record ContextDebugResponse(
        String query,
        List<RerankedCandidateResponse> rerankedCandidates,
        List<SelectedChildChunkResponse> selectedChildChunks,
        List<ExpandedParentContextResponse> expandedParentContexts,
        List<CitationResponse> citations,
        String finalContextText,
        ContextDebugMetadata debugMetadata
) {

    public static ContextDebugResponse from(ContextBuildResult result) {
        return new ContextDebugResponse(
                result.query(),
                result.rerankedCandidates().stream().map(RerankedCandidateResponse::from).toList(),
                result.selectedChildChunks().stream().map(SelectedChildChunkResponse::from).toList(),
                result.expandedParentContexts().stream().map(ExpandedParentContextResponse::from).toList(),
                result.citations().stream().map(CitationResponse::from).toList(),
                result.finalContextText(),
                result.debugMetadata()
        );
    }

    public record RerankedCandidateResponse(
            UUID childChunkId,
            UUID parentChunkId,
            UUID documentId,
            int chunkIndex,
            String previewText,
            RetrievalSource source,
            Integer vectorRank,
            Double vectorDistance,
            Integer fullTextRank,
            Double fullTextScore,
            double rrfScore,
            int originalRank,
            int rerankedRank,
            double rerankScore,
            RerankScoreBreakdown scoreBreakdown,
            String reason
    ) {

        static RerankedCandidateResponse from(RerankedCandidate candidate) {
            return new RerankedCandidateResponse(
                    candidate.candidate().childChunkId(),
                    candidate.candidate().parentChunkId(),
                    candidate.candidate().documentId(),
                    candidate.candidate().chunkIndex(),
                    candidate.candidate().previewText(),
                    candidate.candidate().source(),
                    candidate.candidate().vectorRank(),
                    candidate.candidate().vectorDistance(),
                    candidate.candidate().fullTextRank(),
                    candidate.candidate().fullTextScore(),
                    candidate.candidate().rrfScore(),
                    candidate.originalRank(),
                    candidate.rerankedRank(),
                    candidate.rerankScore(),
                    candidate.scoreBreakdown(),
                    candidate.reason()
            );
        }
    }

    public record SelectedChildChunkResponse(
            UUID childChunkId,
            UUID parentChunkId,
            UUID documentId,
            String originalFilename,
            int chunkIndex,
            int charStart,
            int charEnd,
            String previewText,
            RetrievalSource source,
            int rerankedRank,
            double rerankScore,
            String selectionReason
    ) {

        static SelectedChildChunkResponse from(SelectedChildChunk selectedChildChunk) {
            return new SelectedChildChunkResponse(
                    selectedChildChunk.childChunkId(),
                    selectedChildChunk.parentChunkId(),
                    selectedChildChunk.documentId(),
                    selectedChildChunk.originalFilename(),
                    selectedChildChunk.chunkIndex(),
                    selectedChildChunk.charStart(),
                    selectedChildChunk.charEnd(),
                    selectedChildChunk.previewText(),
                    selectedChildChunk.source(),
                    selectedChildChunk.rerankedRank(),
                    selectedChildChunk.rerankScore(),
                    selectedChildChunk.selectionReason()
            );
        }
    }

    public record ExpandedParentContextResponse(
            UUID parentChunkId,
            UUID documentId,
            String originalFilename,
            int parentChunkIndex,
            int charStart,
            int charEnd,
            String text,
            boolean truncated,
            int includedChars,
            List<UUID> childChunkIds
    ) {

        static ExpandedParentContextResponse from(ExpandedParentContext context) {
            return new ExpandedParentContextResponse(
                    context.parentChunkId(),
                    context.documentId(),
                    context.originalFilename(),
                    context.parentChunkIndex(),
                    context.charStart(),
                    context.charEnd(),
                    context.text(),
                    context.truncated(),
                    context.includedChars(),
                    context.childChunkIds()
            );
        }
    }

    public record CitationResponse(
            int citationIndex,
            String citationMarker,
            UUID documentId,
            String originalFilename,
            UUID parentChunkId,
            UUID childChunkId,
            int chunkIndex,
            String sectionTitle,
            int charStart,
            int charEnd,
            String previewText
    ) {

        static CitationResponse from(Citation citation) {
            return new CitationResponse(
                    citation.citationIndex(),
                    citation.citationMarker(),
                    citation.documentId(),
                    citation.originalFilename(),
                    citation.parentChunkId(),
                    citation.childChunkId(),
                    citation.chunkIndex(),
                    citation.sectionTitle(),
                    citation.charStart(),
                    citation.charEnd(),
                    citation.previewText()
            );
        }
    }
}
