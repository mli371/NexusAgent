package com.nexusagent.query.application;

public record QuestionResolution(String status, String resolvedQuestion, String clarificationQuestion, String reasonCode) {
    public static final String VERSION = "page-follow-up-v1";

    public static QuestionResolution unchanged(String question) {
        return new QuestionResolution("unchanged", question, null, "STANDALONE");
    }

    public static QuestionResolution refused() {
        return new QuestionResolution("refused", null, null, "MODEL_REFUSED");
    }

    public boolean terminal() { return "needs_clarification".equals(status) || "refused".equals(status); }
}
