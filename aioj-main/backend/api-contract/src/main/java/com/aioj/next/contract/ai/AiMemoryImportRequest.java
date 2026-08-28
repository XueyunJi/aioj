package com.aioj.next.contract.ai;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record AiMemoryImportRequest(
        @NotBlank @Size(max = 200000) String markdown,
        @Size(max = 16) String mode
) {
}
