package com.aioj.next.judge.domain;

public class TestcasePackageUnavailableException extends RuntimeException {
    public TestcasePackageUnavailableException(String message) {
        super(message);
    }

    public TestcasePackageUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
