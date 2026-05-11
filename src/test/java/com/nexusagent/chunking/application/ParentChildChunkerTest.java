package com.nexusagent.chunking.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Set;
import java.util.stream.Collectors;

import com.nexusagent.chunking.domain.ParentChildChunkPlan;
import org.junit.jupiter.api.Test;

class ParentChildChunkerTest {

    @Test
    void shortDocumentProducesOneParentAndOneChild() {
        ParentChildChunkPlan plan = chunker(200, 80, 20).chunk("Short document with enough text.");

        assertThat(plan.parentChunks()).hasSize(1);
        assertThat(plan.childChunks()).hasSize(1);
        assertThat(plan.parentChunks().get(0).text()).isEqualTo("Short document with enough text.");
        assertThat(plan.childChunks().get(0).text()).isEqualTo("Short document with enough text.");
        assertThat(plan.childChunks().get(0).parentChunkId()).isEqualTo(plan.parentChunks().get(0).id());
    }

    @Test
    void splitsParagraphsIntoLargerParentChunks() {
        String text = """
                Alpha paragraph has enough words.

                Beta paragraph has enough words.

                Gamma paragraph has enough words.
                """;

        ParentChildChunkPlan plan = chunker(70, 80, 20).chunk(text);

        assertThat(plan.parentChunks()).hasSize(2);
        assertThat(plan.parentChunks().get(0).text()).contains("Alpha paragraph").contains("Beta paragraph");
        assertThat(plan.parentChunks().get(1).text()).contains("Gamma paragraph");
    }

    @Test
    void createsOverlappingSlidingWindowChildChunksInsideParent() {
        String text = "alpha beta gamma delta epsilon zeta eta theta iota kappa lambda";

        ParentChildChunkPlan plan = chunker(500, 30, 8).chunk(text);

        assertThat(plan.parentChunks()).hasSize(1);
        assertThat(plan.childChunks()).hasSizeGreaterThan(1);
        assertThat(plan.childChunks())
                .allSatisfy(child -> {
                    assertThat(child.parentChunkId()).isEqualTo(plan.parentChunks().get(0).id());
                    assertThat(child.charStart()).isGreaterThanOrEqualTo(plan.parentChunks().get(0).charStart());
                    assertThat(child.charEnd()).isLessThanOrEqualTo(plan.parentChunks().get(0).charEnd());
                    assertThat(child.text().length()).isLessThanOrEqualTo(30);
                });
        assertThat(plan.childChunks().get(1).charStart()).isLessThan(plan.childChunks().get(0).charEnd());
    }

    @Test
    void childChunksReferenceExistingParentChunks() {
        String text = "First parent text is here.\n\nSecond parent text is here and longer.";

        ParentChildChunkPlan plan = chunker(50, 20, 5).chunk(text);
        Set<java.util.UUID> parentIds = plan.parentChunks().stream()
                .map(parent -> parent.id())
                .collect(Collectors.toSet());

        assertThat(plan.parentChunks()).hasSize(2);
        assertThat(plan.childChunks()).isNotEmpty();
        assertThat(plan.childChunks())
                .allSatisfy(child -> assertThat(parentIds).contains(child.parentChunkId()));
    }

    @Test
    void emptyDocumentProducesNoChunks() {
        ParentChildChunkPlan plan = chunker(100, 30, 5).chunk("   \n\n ");

        assertThat(plan.parentChunks()).isEmpty();
        assertThat(plan.childChunks()).isEmpty();
    }

    @Test
    void longParagraphSplitsIntoParentChunksWithoutExceedingLimit() {
        String text = "word ".repeat(120);

        ParentChildChunkPlan plan = chunker(120, 50, 10).chunk(text);

        assertThat(plan.parentChunks()).hasSizeGreaterThan(1);
        assertThat(plan.parentChunks())
                .allSatisfy(parent -> assertThat(parent.text().length()).isLessThanOrEqualTo(120));
        assertThat(plan.parentChunks().get(0).charStart()).isEqualTo(0);
        assertThat(plan.parentChunks().get(plan.parentChunks().size() - 1).charEnd()).isLessThanOrEqualTo(text.length());
    }

    private ParentChildChunker chunker(int parentMaxChars, int childMaxChars, int childOverlapChars) {
        ChunkingProperties properties = new ChunkingProperties();
        properties.setParentMaxChars(parentMaxChars);
        properties.setChildMaxChars(childMaxChars);
        properties.setChildOverlapChars(childOverlapChars);
        return new ParentChildChunker(properties);
    }
}
