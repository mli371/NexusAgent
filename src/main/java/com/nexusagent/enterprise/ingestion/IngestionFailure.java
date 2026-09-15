package com.nexusagent.enterprise.ingestion;

import java.util.concurrent.TimeoutException;

import com.nexusagent.common.error.BadRequestException;
import com.nexusagent.common.error.OperationException;
import org.springframework.dao.TransientDataAccessException;

/** Typed failures support policy decisions without parsing potentially sensitive messages. */
public class IngestionFailure extends BadRequestException {
    private final String code;

    public IngestionFailure(String code, String message) {
        super(message);
        this.code = code;
    }

    public static String code(Throwable error) {
        for (int depth = 0; error != null && depth < 8; depth++, error = error.getCause()) {
            if (error instanceof IngestionFailure failure) { return failure.code; }
            if (error instanceof OperationException failure) { return failure.code(); }
            if (error instanceof TimeoutException || error instanceof TransientDataAccessException
                    || error instanceof java.net.SocketTimeoutException) { return "TRANSIENT_DEPENDENCY"; }
        }
        return "UNKNOWN";
    }
}
