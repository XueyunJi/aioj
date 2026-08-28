package com.aioj.next.common.error;

public class DomainException extends RuntimeException {
    private final ErrorCode errorCode;

    public DomainException(ErrorCode errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }

    public DomainException(ErrorCode errorCode) {
        this(errorCode, errorCode.message());
    }

    public ErrorCode errorCode() {
        return errorCode;
    }
}
