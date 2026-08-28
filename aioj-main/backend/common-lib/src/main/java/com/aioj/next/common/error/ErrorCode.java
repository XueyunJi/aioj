package com.aioj.next.common.error;

public enum ErrorCode {
    BAD_REQUEST(40000, "Bad request"),
    VALIDATION_FAILED(40001, "Request validation failed"),
    INVALID_PAYLOAD(40002, "Malformed request body"),
    MISSING_PARAMETER(40003, "Missing required parameter"),
    TYPE_MISMATCH(40004, "Parameter type mismatch"),
    UNAUTHORIZED(40100, "Unauthorized"),
    FORBIDDEN(40300, "Forbidden"),
    NOT_FOUND(40400, "Not found"),
    METHOD_NOT_ALLOWED(40500, "Method not allowed"),
    CONFLICT(40900, "Conflict"),
    PAYLOAD_TOO_LARGE(41300, "Payload too large"),
    TOO_MANY_REQUESTS(42900, "Too many requests"),
    INTERNAL_ERROR(50000, "Internal server error"),
    SERVICE_UNAVAILABLE(50300, "Upstream service unavailable");

    private final int code;
    private final String message;

    ErrorCode(int code, String message) {
        this.code = code;
        this.message = message;
    }

    public int code() {
        return code;
    }

    public String message() {
        return message;
    }
}
