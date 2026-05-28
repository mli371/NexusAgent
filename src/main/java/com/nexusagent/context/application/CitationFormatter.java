package com.nexusagent.context.application;

import java.util.List;

import com.nexusagent.context.domain.Citation;
import com.nexusagent.context.domain.ExpandedCandidateContext;
import com.nexusagent.context.domain.ExpandedParentContext;
import org.springframework.stereotype.Service;

@Service
public class CitationFormatter {

    public Citation citation(int citationIndex, ExpandedCandidateContext context) {
        String previewText = context.rerankedCandidate().candidate().previewText();
        return new Citation(
                citationIndex,
                marker(citationIndex),
                context.document().id(),
                context.document().originalFilename(),
                context.parentChunk().id(),
                context.childChunk().id(),
                context.childChunk().chunkIndex(),
                null,
                context.childChunk().charStart(),
                context.childChunk().charEnd(),
                previewText
        );
    }

    public String formatContext(List<ExpandedParentContext> parentContexts, List<Citation> citations) {
        StringBuilder builder = new StringBuilder();
        for (int index = 0; index < parentContexts.size(); index++) {
            ExpandedParentContext parentContext = parentContexts.get(index);
            Citation citation = citations.get(index);
            if (builder.length() > 0) {
                builder.append("\n\n");
            }
            builder.append(citation.citationMarker())
                    .append(" ")
                    .append(parentContext.originalFilename())
                    .append(" parent_chunk=")
                    .append(parentContext.parentChunkId())
                    .append("\n")
                    .append(parentContext.text());
            if (parentContext.truncated()) {
                builder.append("\n[truncated]");
            }
        }
        return builder.toString();
    }

    private String marker(int citationIndex) {
        return "[C%d]".formatted(citationIndex);
    }
}
