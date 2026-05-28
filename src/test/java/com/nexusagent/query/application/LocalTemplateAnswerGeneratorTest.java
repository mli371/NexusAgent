package com.nexusagent.query.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.UUID;

import com.nexusagent.context.domain.Citation;
import com.nexusagent.context.domain.ContextBuildResult;
import com.nexusagent.context.domain.ContextDebugMetadata;
import org.junit.jupiter.api.Test;
import reactor.test.StepVerifier;

class LocalTemplateAnswerGeneratorTest {

    private final LocalTemplateAnswerGenerator generator = new LocalTemplateAnswerGenerator();

    @Test
    void generatesLocalPlaceholderAnswerFromContextAndCitations() {
        UUID documentId = UUID.randomUUID();
        ContextBuildResult context = new ContextBuildResult(
                "security policy",
                List.of(),
                List.of(),
                List.of(),
                List.of(new Citation(
                        1,
                        "[C1]",
                        documentId,
                        "policy.txt",
                        UUID.randomUUID(),
                        UUID.randomUUID(),
                        0,
                        null,
                        0,
                        20,
                        "security policy"
                )),
                "[C1] policy.txt\nSecurity policy access controls require review.",
                new ContextDebugMetadata("deterministic-heuristic", 1, 1, 1, 1, 1000, 60, 0, 0)
        );

        StepVerifier.create(generator.generate("What is the security policy?", context))
                .assertNext(answer -> {
                    assertThat(answer.generatorName()).isEqualTo("local-template");
                    assertThat(answer.answer()).contains("LocalTemplateAnswerGenerator placeholder answer");
                    assertThat(answer.answer()).contains("Security policy access controls");
                    assertThat(answer.answer()).contains("[C1] policy.txt");
                    assertThat(answer.limitations()).anySatisfy(limitation ->
                            assertThat(limitation).contains("no LLM was called"));
                })
                .verifyComplete();
    }

    @Test
    void returnsNoContextAnswerWhenContextIsEmpty() {
        ContextBuildResult context = new ContextBuildResult(
                "security policy",
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                "",
                new ContextDebugMetadata("deterministic-heuristic", 0, 0, 0, 0, 1000, 0, 0, 0)
        );

        StepVerifier.create(generator.generate("What is the security policy?", context))
                .assertNext(answer -> {
                    assertThat(answer.answer()).contains("insufficient retrieved context");
                    assertThat(answer.answer()).contains("cannot provide a grounded answer");
                    assertThat(answer.answer()).doesNotContain("Sources:");
                    assertThat(answer.answer()).doesNotContain("[C1]");
                    assertThat(answer.limitations()).anySatisfy(limitation ->
                            assertThat(limitation).contains("No retrieved context was available"));
                })
                .verifyComplete();
    }
}
