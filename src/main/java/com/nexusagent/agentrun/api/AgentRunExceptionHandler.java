package com.nexusagent.agentrun.api;

import java.util.Map;

import com.nexusagent.agentrun.application.RunException;
import com.nexusagent.common.error.BadRequestException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.core.io.buffer.DataBufferLimitException;
import org.springframework.dao.DataAccessException;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ServerWebInputException;

@Order(Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice(assignableTypes = {AgentRunController.class, AgentWorkerController.class, RunEventStreamController.class, ToolObservationController.class})
public class AgentRunExceptionHandler {
    private static final Logger log = LoggerFactory.getLogger(AgentRunExceptionHandler.class);

    @ExceptionHandler(RunException.class)
    public ResponseEntity<Map<String, Object>> runError(RunException error) {
        return response(error.status(), error.code(), error.getMessage());
    }

    @ExceptionHandler({ServerWebInputException.class, BadRequestException.class})
    public ResponseEntity<Map<String, Object>> invalid(Exception error) {
        return response(400, "INVALID_INPUT", "Invalid request input or required headers");
    }

    @ExceptionHandler(DataBufferLimitException.class)
    public ResponseEntity<Map<String, Object>> tooLarge(Exception error) {
        return response(413, "PAYLOAD_TOO_LARGE", "Request exceeds the byte limit");
    }

    @ExceptionHandler(DataAccessException.class)
    public ResponseEntity<Map<String, Object>> database(Exception error) {
        log.warn("agent_run_storage_unavailable type={}", error.getClass().getSimpleName());
        return response(503, "STORAGE_UNAVAILABLE", "Run storage temporarily unavailable");
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> unexpected(Exception error) {
        log.error("agent_run_error type={}", error.getClass().getSimpleName());
        return response(500, "INTERNAL_ERROR", "Unexpected run error");
    }

    private ResponseEntity<Map<String, Object>> response(int status, String code, String message) {
        return ResponseEntity.status(status).body(Map.of("status", status, "code", code, "message", message));
    }
}
