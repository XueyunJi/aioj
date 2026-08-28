package com.aioj.next.contract.ai;

public record AccountImportParsedUser(
        String account,
        String displayName,
        String email,
        double confidence,
        String sourceLine
) {
}
