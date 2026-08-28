package com.aioj.next.contract.submission;

import com.fasterxml.jackson.annotation.JsonInclude;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record SubmissionCreateRequest(
        @NotNull Long problemId,
        @NotBlank String language,
        @NotBlank @Size(max = SubmissionCreateRequest.MAX_SOURCE_CODE_CHARS) String code,
        @JsonInclude(JsonInclude.Include.NON_NULL)
        Long contestId,
        @JsonInclude(JsonInclude.Include.NON_NULL)
        Long contestRunId,
        @JsonInclude(JsonInclude.Include.NON_NULL)
        Long contestProblemId
) {
    /** Upper bound for submitted source code, in characters (256 KiB). */
    public static final int MAX_SOURCE_CODE_CHARS = 256 * 1024;
}
