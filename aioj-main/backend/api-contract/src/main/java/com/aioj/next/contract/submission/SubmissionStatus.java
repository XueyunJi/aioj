package com.aioj.next.contract.submission;

public enum SubmissionStatus {
    QUEUED,
    RUNNING,
    ACCEPTED,
    WRONG_ANSWER,
    COMPILE_ERROR,
    RUNTIME_ERROR,
    TIME_LIMIT_EXCEEDED,
    MEMORY_LIMIT_EXCEEDED,
    OUTPUT_LIMIT_EXCEEDED,
    SYSTEM_ERROR
}
