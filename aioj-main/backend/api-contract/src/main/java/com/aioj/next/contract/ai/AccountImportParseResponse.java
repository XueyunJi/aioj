package com.aioj.next.contract.ai;

import java.util.List;

public record AccountImportParseResponse(
        List<AccountImportParsedUser> users,
        String note
) {
}
