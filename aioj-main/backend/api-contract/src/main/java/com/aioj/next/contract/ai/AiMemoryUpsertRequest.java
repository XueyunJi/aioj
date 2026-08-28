package com.aioj.next.contract.ai;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;

public record AiMemoryUpsertRequest(
        @Size(max = 32) String category,
        @Size(max = 160) String title,
        @NotBlank @Size(max = 48) String memoryType,
        @NotBlank @Size(max = 20000) String content,
        @DecimalMin("0.000") @DecimalMax("1.000") BigDecimal confidence,
        @Size(max = 24) String status
) {
}
