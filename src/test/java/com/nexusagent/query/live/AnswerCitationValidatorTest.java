package com.nexusagent.query.live;

import static org.assertj.core.api.Assertions.*;
import java.util.List;
import com.nexusagent.common.error.OperationException;
import com.nexusagent.context.domain.ContextBuildResult;
import com.nexusagent.query.application.GeneratedAnswer;
import org.junit.jupiter.api.Test;

class AnswerCitationValidatorTest {
    final AnswerCitationValidator validator = new AnswerCitationValidator();

    @Test
    void markerSetsMustAgreeAndContainKnownSources() {
        for (GeneratedAnswer invalid : List.of(
                LiveQueryServiceTest.answer("No citations", List.of("[C1]")),
                LiveQueryServiceTest.answer("Claim [C1]", List.of()),
                LiveQueryServiceTest.answer("Claim [C1]", List.of("[C1]", "[C1]")),
                LiveQueryServiceTest.answer("Claim [C9]", List.of("[C9]")))) {
            assertThatThrownBy(() -> validator.validate(invalid, LiveQueryServiceTest.evidence())).isInstanceOf(OperationException.class);
        }
    }

    @Test
    void citationNeedsBothSelectedChildAndExpandedParent() {
        var original = LiveQueryServiceTest.evidence();
        for (ContextBuildResult invalid : List.of(
                new ContextBuildResult(original.query(), List.of(), List.of(), original.expandedParentContexts(), original.citations(), original.finalContextText(), original.debugMetadata()),
                new ContextBuildResult(original.query(), List.of(), original.selectedChildChunks(), List.of(), original.citations(), original.finalContextText(), original.debugMetadata()))) {
            assertThatThrownBy(() -> validator.validate(LiveQueryServiceTest.answer("Claim [C1]", List.of("[C1]")), invalid))
                    .isInstanceOf(OperationException.class);
        }
    }

    @Test
    void abstentionAndRefusalHaveNoCitations() {
        for (String status : List.of("insufficient_context", "refused")) {
            assertThat(validator.validate(new GeneratedAnswer("No answer", "openai", List.of(), status, List.of()),
                    LiveQueryServiceTest.evidence())).isEmpty();
            assertThatThrownBy(() -> validator.validate(new GeneratedAnswer("No answer [C1]", "openai", List.of(), status, List.of("[C1]")),
                    LiveQueryServiceTest.evidence())).isInstanceOf(OperationException.class);
        }
    }

    @Test void partiallyVisibleChildCannotBeCited() {
        var original = LiveQueryServiceTest.evidence();
        var parent = original.expandedParentContexts().get(0);
        var shortened = new com.nexusagent.context.domain.ExpandedParentContext(parent.parentChunkId(), parent.documentId(),
                parent.originalFilename(), parent.parentChunkIndex(), parent.charStart(), parent.charStart() + 1,
                parent.text().substring(0, 1), true, 1, parent.childChunkIds());
        var invalid = new ContextBuildResult(original.query(), original.rerankedCandidates(), original.selectedChildChunks(),
                List.of(shortened), original.citations(), original.finalContextText(), original.debugMetadata());
        assertThatThrownBy(() -> validator.validate(LiveQueryServiceTest.answer("Claim [C1]", List.of("[C1]")), invalid))
                .isInstanceOf(OperationException.class);
    }
}
