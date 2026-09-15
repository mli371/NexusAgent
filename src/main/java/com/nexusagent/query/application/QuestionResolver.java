package com.nexusagent.query.application;

import java.util.List;
import com.nexusagent.query.api.ConversationTurn;
import reactor.core.publisher.Mono;

public interface QuestionResolver {
    Mono<QuestionResolution> resolve(String question, List<ConversationTurn> history);
}
