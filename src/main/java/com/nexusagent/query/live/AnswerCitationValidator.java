package com.nexusagent.query.live;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import com.nexusagent.common.error.OperationException;
import com.nexusagent.context.domain.Citation;
import com.nexusagent.context.domain.ContextBuildResult;
import com.nexusagent.query.application.GeneratedAnswer;

final class AnswerCitationValidator {
    private static final Pattern MARKER = Pattern.compile("\\[C[^\\]\\r\\n]*\\]");

    List<Citation> validate(GeneratedAnswer answer, ContextBuildResult context) {
        Set<String> declared = new HashSet<>(answer.usedCitationMarkers());
        Set<String> inline = MARKER.matcher(answer.answer()).results().map(match -> match.group()).collect(Collectors.toSet());
        if (!inline.equals(declared) || declared.size() != answer.usedCitationMarkers().size()) { throw invalid(); }
        if (Set.of("insufficient_context", "refused").contains(answer.status())) {
            if (!declared.isEmpty()) { throw invalid(); }
            return List.of();
        }
        if (!"answered".equals(answer.status()) || answer.answer().isBlank() || declared.isEmpty()
                || context.finalContextText().isBlank()) { throw invalid(); }
        List<Citation> used = context.citations().stream().filter(c -> declared.contains(c.citationMarker())).toList();
        if (used.size() != declared.size()) { throw invalid(); }
        for (Citation citation : used) {
            boolean child = context.selectedChildChunks().stream().anyMatch(c -> c.childChunkId().equals(citation.childChunkId())
                    && c.parentChunkId().equals(citation.parentChunkId()) && c.documentId().equals(citation.documentId())
                    && c.chunkIndex() == citation.chunkIndex() && c.charStart() == citation.charStart()
                    && c.charEnd() == citation.charEnd() && c.originalFilename().equals(citation.originalFilename()));
            boolean parent = context.expandedParentContexts().stream().anyMatch(p -> p.parentChunkId().equals(citation.parentChunkId())
                    && p.documentId().equals(citation.documentId()) && p.childChunkIds().contains(citation.childChunkId()));
            if (!child || !parent || !context.finalContextText().contains(citation.citationMarker())) { throw invalid(); }
        }
        return used;
    }

    private OperationException invalid() { return OperationException.invalidModelResponse(); }
}
