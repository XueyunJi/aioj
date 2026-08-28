package com.aioj.next.contract.auth;

public record DailyUserActivityResponse(
        String date,
        long activeUsers,
        long newUsers
) {
}
