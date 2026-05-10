package com.nexusagent.common.error;

import java.time.Instant;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.ServerWebInputException;

@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(BadRequestException.class)
    public ResponseEntity<ApiError> handleBadRequest(BadRequestException exception, ServerWebExchange exchange) {
        return buildError(HttpStatus.BAD_REQUEST, exception.getMessage(), exchange);
    }

    @ExceptionHandler(NotFoundException.class)
    public ResponseEntity<ApiError> handleNotFound(NotFoundException exception, ServerWebExchange exchange) {
        return buildError(HttpStatus.NOT_FOUND, exception.getMessage(), exchange);
    }

    @ExceptionHandler(ServerWebInputException.class)
    public ResponseEntity<ApiError> handleInputError(ServerWebInputException exception, ServerWebExchange exchange) {
        return buildError(HttpStatus.BAD_REQUEST, "Invalid request input", exchange);
    }

    @ExceptionHandler(Throwable.class)
    public ResponseEntity<ApiError> handleUnexpected(Throwable exception, ServerWebExchange exchange) {
        log.error("Unexpected API error", exception);
        return buildError(HttpStatus.INTERNAL_SERVER_ERROR, "Unexpected server error", exchange);
    }

    private ResponseEntity<ApiError> buildError(HttpStatus status, String message, ServerWebExchange exchange) {
        ApiError error = new ApiError(
                Instant.now(),
                status.value(),
                status.getReasonPhrase(),
                message,
                exchange.getRequest().getPath().value()
        );
        return ResponseEntity.status(status).body(error);
    }
}
