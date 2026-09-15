package com.nexusagent.query.live;

import java.util.concurrent.TimeoutException;
import com.nexusagent.common.error.BadRequestException;
import com.nexusagent.common.error.OperationException;
import org.springframework.http.HttpStatus;

public final class QueryFailure extends OperationException {
    private final String traceId;

    private QueryFailure(String traceId, HttpStatus status, String code, String message) {
        super(status, code, message);
        this.traceId = traceId;
    }

    public String traceId() { return traceId; }

    public static QueryFailure safe(String traceId, Throwable error) {
        if (error instanceof QueryFailure failure) { return failure; }
        if (error instanceof OperationException operation) {
            return new QueryFailure(traceId, operation.status(), operation.code(), operation.getMessage());
        }
        if (error instanceof BadRequestException) {
            return new QueryFailure(traceId, HttpStatus.BAD_REQUEST, "INVALID_QUERY", error.getMessage());
        }
        if (error instanceof TimeoutException) {
            return new QueryFailure(traceId, HttpStatus.GATEWAY_TIMEOUT, "QUERY_TIMEOUT", "Query stage timed out");
        }
        return new QueryFailure(traceId, HttpStatus.SERVICE_UNAVAILABLE, "QUERY_UNAVAILABLE",
                "Query could not be completed; retry explicitly after checking document readiness");
    }
}
