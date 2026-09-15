package com.nexusagent.common.error;

import org.springframework.http.HttpStatus;

/** Only application-owned, non-sensitive messages belong in this exception. */
public class OperationException extends RuntimeException {
    private final HttpStatus status;
    private final String code;

    public OperationException(HttpStatus status, String code, String message) {
        super(message);
        this.status = status;
        this.code = code;
    }

    public HttpStatus status() { return status; }
    public String code() { return code; }

    public static OperationException invalidModelResponse() {
        return new OperationException(HttpStatus.BAD_GATEWAY, "INVALID_MODEL_RESPONSE",
                "Model returned an invalid or incomplete response");
    }

    public static OperationException modelMismatch() {
        return new OperationException(HttpStatus.CONFLICT, "EMBEDDING_MODEL_MISMATCH",
                "Existing embeddings use another model; explicitly confirm replaceExisting=true to rebuild");
    }

    public static OperationException documentChanged() {
        return new OperationException(HttpStatus.CONFLICT, "DOCUMENT_CHANGED",
                "Document chunks changed during embedding; no generated vectors were committed");
    }
}
