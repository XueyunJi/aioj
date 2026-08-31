package com.aioj.next.contract.problem;

import java.util.List;

public record TutorProblemCapabilitiesResponse(
        String apiVersion,
        String visibility,
        String tagSource,
        List<String> operations,
        List<String> sortableFields,
        List<String> difficultyValues
) {
}
