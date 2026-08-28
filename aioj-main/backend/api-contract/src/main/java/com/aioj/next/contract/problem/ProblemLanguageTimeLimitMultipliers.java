package com.aioj.next.contract.problem;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;

import java.math.BigDecimal;

public record ProblemLanguageTimeLimitMultipliers(
        @DecimalMin("1.0") @DecimalMax("10.0") BigDecimal cpp,
        @DecimalMin("1.0") @DecimalMax("10.0") BigDecimal python,
        @DecimalMin("1.0") @DecimalMax("10.0") BigDecimal java
) {
}
