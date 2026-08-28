package com.aioj.next.judge.domain;

public class NonRetryableJudgeTaskException extends RuntimeException {
    public NonRetryableJudgeTaskException(String message) {
        super(message);
    }
}
