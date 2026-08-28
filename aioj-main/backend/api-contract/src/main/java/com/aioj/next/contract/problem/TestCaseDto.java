package com.aioj.next.contract.problem;

import jakarta.validation.constraints.NotBlank;

public record TestCaseDto(@NotBlank String input, @NotBlank String expectedOutput, boolean sample) {
}
