package com.aioj.next.contract.auth;

public record HandoffExchangeResponse(
        TokenResponse tokens,
        String nextPath
) {
}
