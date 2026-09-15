package com.nexusagent.agentrun.application;

public class RunException extends RuntimeException {
    private final int status;
    private final String code;

    public RunException(int status, String code, String message) {
        super(message);
        this.status = status;
        this.code = code;
    }

    public int status() { return status; }
    public String code() { return code; }

    public static RunException invalid(String message) {
        return new RunException(400, "INVALID_INPUT", message);
    }

    public static RunException missing() {
        return new RunException(404, "NOT_FOUND", "Resource not found or not accessible");
    }

    public static RunException conflict(String message) {
        return new RunException(409, "STATE_CONFLICT", message);
    }
}
