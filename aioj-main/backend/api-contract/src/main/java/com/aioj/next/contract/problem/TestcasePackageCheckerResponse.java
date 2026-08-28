package com.aioj.next.contract.problem;

import com.fasterxml.jackson.annotation.JsonInclude;

public record TestcasePackageCheckerResponse(
        TestcaseCheckerType type,
        @JsonInclude(JsonInclude.Include.NON_NULL)
        String language,
        @JsonInclude(JsonInclude.Include.NON_NULL)
        String source,
        @JsonInclude(JsonInclude.Include.NON_NULL)
        TestcaseCheckerProtocol protocol
) {
}
