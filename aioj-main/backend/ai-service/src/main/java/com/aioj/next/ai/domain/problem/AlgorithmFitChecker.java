package com.aioj.next.ai.domain.problem;

import com.aioj.next.contract.ai.ProblemDraftRequest;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class AlgorithmFitChecker {
    private static final int RATING_TOLERANCE = 250;
    private static final Pattern LARGE_INTEGER = Pattern.compile("\\d{5,}");

    public AlgorithmFitCheckResult check(ProblemDraftRequest request, ProblemDraftPlanningResult planningResult) {
        List<String> errors = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        ProblemDesignPlan plan = planningResult == null ? null : planningResult.problemDesignPlan();
        ProblemDesignFitCheck fitCheck = planningResult == null ? null : planningResult.fitCheck();

        if (plan == null) {
            errors.add("problemDesignPlan is required");
            return AlgorithmFitCheckResult.failed(errors, warnings);
        }

        checkRequestedAlgorithms(request, plan, errors);
        checkRating(request, plan, errors);
        checkHighRatingPlanCompleteness(request, plan, errors);
        checkModelFitCheck(fitCheck, errors);
        checkObviousComplexityContradiction(plan, errors);

        return errors.isEmpty()
                ? AlgorithmFitCheckResult.passed(warnings)
                : AlgorithmFitCheckResult.failed(errors, warnings);
    }

    private void checkRequestedAlgorithms(ProblemDraftRequest request, ProblemDesignPlan plan, List<String> errors) {
        List<String> tokens = algorithmTokens(request == null ? null : request.algorithm());
        if (tokens.isEmpty()) {
            return;
        }
        String core = text(plan.coreAlgorithm());
        String haystack = String.join(" ",
                core,
                String.join(" ", plan.secondaryAlgorithms()),
                text(plan.coreObservation())
        );
        List<String> missing = tokens.stream()
                .filter(token -> !containsToken(haystack, token))
                .toList();
        if (!missing.isEmpty()) {
            errors.add("requested algorithm tokens are missing from design plan: " + String.join(", ", missing));
        }
        boolean anyCore = tokens.stream().anyMatch(token -> containsToken(core, token));
        if (!anyCore) {
            errors.add("at least one requested algorithm token must appear in coreAlgorithm");
        }
    }

    private void checkRating(ProblemDraftRequest request, ProblemDesignPlan plan, List<String> errors) {
        Integer target = request == null ? null : request.cfRating();
        if (target == null) {
            return;
        }
        Integer estimated = plan.estimatedCfRating();
        if (estimated == null) {
            errors.add("estimatedCfRating is required when cfRating is requested");
            return;
        }
        if (Math.abs(estimated - target) > RATING_TOLERANCE) {
            errors.add("estimatedCfRating " + estimated + " is outside target cfRating " + target + " +/- " + RATING_TOLERANCE);
        }
    }

    private void checkHighRatingPlanCompleteness(ProblemDraftRequest request, ProblemDesignPlan plan, List<String> errors) {
        Integer rating = request == null ? null : request.cfRating();
        if (rating == null || rating < 1700) {
            return;
        }
        if (!hasText(plan.constraints())) {
            errors.add("constraints are required for cfRating >= 1700");
        }
        if (!hasText(plan.expectedTimeComplexity())) {
            errors.add("expectedTimeComplexity is required for cfRating >= 1700");
        }
        if (plan.boundaryCases().isEmpty()) {
            errors.add("boundaryCases are required for cfRating >= 1700");
        }
        if (plan.commonWrongApproaches().isEmpty()) {
            errors.add("commonWrongApproaches are required for cfRating >= 1700");
        }
    }

    private void checkModelFitCheck(ProblemDesignFitCheck fitCheck, List<String> errors) {
        if (fitCheck == null) {
            errors.add("fitCheck is required");
            return;
        }
        if (fitCheck.explicitlyRejected()) {
            errors.add("model fitCheck rejected the plan"
                    + (fitCheck.violations().isEmpty() ? "" : ": " + String.join("; ", fitCheck.violations())));
        }
    }

    private void checkObviousComplexityContradiction(ProblemDesignPlan plan, List<String> errors) {
        if (!hasLargeScaleConstraint(plan.constraints()) || !isNaiveHighComplexity(plan.expectedTimeComplexity())) {
            return;
        }
        errors.add("large constraints conflict with declared naive high complexity: " + text(plan.expectedTimeComplexity()));
    }

    private List<String> algorithmTokens(String algorithm) {
        if (!hasText(algorithm)) {
            return List.of();
        }
        String[] rawTokens = algorithm.split("[,，、\\s]+");
        Set<String> tokens = new LinkedHashSet<>();
        for (String raw : rawTokens) {
            String token = normalize(raw);
            if (!token.isBlank()) {
                tokens.add(token);
            }
        }
        return List.copyOf(tokens);
    }

    private boolean containsToken(String value, String token) {
        if (!hasText(value) || !hasText(token)) {
            return false;
        }
        String normalizedValue = normalize(value);
        String normalizedToken = normalize(token);
        String compactValue = compact(normalizedValue);
        String compactToken = compact(normalizedToken);
        return normalizedValue.contains(normalizedToken) || compactValue.contains(compactToken);
    }

    private boolean hasLargeScaleConstraint(String constraints) {
        if (!hasText(constraints)) {
            return false;
        }
        String compact = compact(normalize(constraints));
        if (compact.contains("10^5") || compact.contains("1e5") || compact.contains("2e5")
                || compact.contains("10^6") || compact.contains("1e6")) {
            return true;
        }
        Matcher matcher = LARGE_INTEGER.matcher(compact);
        while (matcher.find()) {
            try {
                if (Long.parseLong(matcher.group()) >= 100_000L) {
                    return true;
                }
            } catch (NumberFormatException ignored) {
                return true;
            }
        }
        return false;
    }

    private boolean isNaiveHighComplexity(String complexity) {
        if (!hasText(complexity)) {
            return false;
        }
        String compact = compact(normalize(complexity))
                .replace("²", "^2")
                .replace("³", "^3");
        return compact.contains("o(n^2")
                || compact.contains("o(n*n")
                || compact.contains("o(n^3")
                || compact.contains("o(2^n")
                || compact.contains("o(n!");
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private String text(String value) {
        return value == null ? "" : value;
    }

    private String normalize(String value) {
        return value == null
                ? ""
                : value.trim()
                .toLowerCase(Locale.ROOT)
                .replace('（', '(')
                .replace('）', ')')
                .replace('，', ',');
    }

    private String compact(String value) {
        return value.replaceAll("[\\s_]+", "");
    }
}
