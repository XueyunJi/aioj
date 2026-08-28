package com.aioj.next.contract.ai;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record AccountImportParseRequest(
        @NotBlank @Size(max = 20000) String text
) {
}
