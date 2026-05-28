package com.nexusagent.query.application;

import com.nexusagent.context.domain.ContextBuildResult;
import reactor.core.publisher.Mono;

public interface AnswerGenerator {

    String name();

    Mono<GeneratedAnswer> generate(String question, ContextBuildResult context);
}
