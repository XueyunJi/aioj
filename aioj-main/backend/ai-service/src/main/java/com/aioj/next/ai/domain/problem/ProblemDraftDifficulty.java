package com.aioj.next.ai.domain.problem;

import java.util.Locale;

public final class ProblemDraftDifficulty {
    private ProblemDraftDifficulty() {
    }

    public static String effective(String requestedDifficulty, Integer cfRating) {
        String normalized = normalize(requestedDifficulty);
        if (normalized != null) {
            return normalized;
        }
        String ratingDifficulty = fromCfRating(cfRating);
        return ratingDifficulty == null ? "EASY" : ratingDifficulty;
    }

    public static String fromCfRating(Integer cfRating) {
        if (cfRating == null || cfRating < 800 || cfRating > 3500) {
            return null;
        }
        if (cfRating <= 1200) {
            return "EASY";
        }
        if (cfRating <= 1700) {
            return "MEDIUM";
        }
        if (cfRating <= 2300) {
            return "HARD";
        }
        return "CHALLENGE";
    }

    static RatingRange rangeForDifficulty(String difficulty) {
        String normalized = normalize(difficulty);
        return switch (normalized == null ? "" : normalized) {
            case "EASY" -> new RatingRange("EASY", 800, 1200);
            case "MEDIUM" -> new RatingRange("MEDIUM", 1201, 1700);
            case "HARD" -> new RatingRange("HARD", 1701, 2300);
            case "CHALLENGE" -> new RatingRange("CHALLENGE", 2301, 3500);
            default -> null;
        };
    }

    private static String normalize(String difficulty) {
        if (difficulty == null || difficulty.isBlank()) {
            return null;
        }
        return difficulty.trim().toUpperCase(Locale.ROOT);
    }

    record RatingRange(String difficulty, int min, int max) {
    }
}
