package com.aioj.next.contract.tutor;

import java.math.BigDecimal;
import java.time.Instant;

public record TutorJudgeEventRequest(
        String eventId,
        String eventType,
        String submissionId,
        String userId,
        String problemId,
        String status,
        String evidence,
        BigDecimal score,
        Instant occurredAt,
        int schemaVersion
) {
    public static final String EVENT_TYPE = "submission.judged";
    public static final int SCHEMA_VERSION = 1;
}
