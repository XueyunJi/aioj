package com.aioj.next.contract.submission;

public record DailySubmissionStatsResponse(
        String date,
        long totalSubmissions,
        long acceptedSubmissions
) {
}
