package com.nexusagent.query.api;

import java.io.IOException;
import java.util.List;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.nexusagent.common.error.BadRequestException;

/** Client-supplied conversational hints, never an authorization or evidence record. */
@JsonDeserialize(using = ConversationTurn.Deserializer.class)
public record ConversationTurn(String question, String answer, boolean answerTruncated) {
    public static final int MAX_TURNS = 3;
    public static final int MAX_ANSWER_CHARS = 1000;

    public static List<ConversationTurn> validated(List<ConversationTurn> history) {
        if (history == null) { return List.of(); }
        if (history.size() > MAX_TURNS) { throw new BadRequestException("history must contain at most 3 turns"); }
        for (var turn : history) {
            if (turn == null || turn.question() == null || turn.question().isBlank() || turn.question().length() > 2000
                    || turn.answer() == null || turn.answer().length() > MAX_ANSWER_CHARS) {
                throw new BadRequestException("Each history turn requires question (1-2000 characters) and answer (0-1000 characters)");
            }
        }
        return List.copyOf(history);
    }

    public static final class Deserializer extends JsonDeserializer<ConversationTurn> {
        @Override
        public ConversationTurn deserialize(JsonParser parser, DeserializationContext context) throws IOException {
            var node = parser.getCodec().readTree(parser);
            if (!(node instanceof com.fasterxml.jackson.databind.JsonNode value) || !value.isObject()
                    || !value.path("question").isTextual() || !value.path("answer").isTextual()
                    || (value.has("answerTruncated") && !value.get("answerTruncated").isBoolean())
                    || value.size() != (value.has("answerTruncated") ? 3 : 2)) {
                throw JsonMappingException.from(parser, "history turns require string question/answer and optional boolean answerTruncated only");
            }
            return new ConversationTurn(value.get("question").textValue(), value.get("answer").textValue(),
                    value.path("answerTruncated").asBoolean(false));
        }
    }
}
