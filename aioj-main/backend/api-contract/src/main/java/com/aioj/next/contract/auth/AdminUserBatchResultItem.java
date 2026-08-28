package com.aioj.next.contract.auth;

public record AdminUserBatchResultItem(
        int rowNumber,
        String account,
        String status,
        String message,
        AdminUserResponse user
) {
}
