package com.aioj.next.contract.contest;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

public record PlagiarismPairDetailResponse(
        PlagiarismPairResponse pair,
        List<PlagiarismFragmentResponse> fragments,
        @JsonInclude(JsonInclude.Include.NON_NULL)
        String aiAnalysis,
        @JsonInclude(JsonInclude.Include.NON_NULL)
        String aiErrorMessage
) {
}
