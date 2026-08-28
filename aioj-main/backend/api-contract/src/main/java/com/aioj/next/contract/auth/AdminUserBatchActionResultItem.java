package com.aioj.next.contract.auth;

public record AdminUserBatchActionResultItem(
        Long userId,
        String account,
        String status,
        String message,
        AdminUserResponse user
) {
}
