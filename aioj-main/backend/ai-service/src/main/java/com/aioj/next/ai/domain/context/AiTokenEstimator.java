package com.aioj.next.ai.domain.context;

/**
 * Composition-based prompt token estimator (W1.8).
 *
 * Replaces the flat chars/4 heuristic with per-character-class weights:
 * CJK chars cost more than ASCII prose, and code/JSON punctuation gets its
 * own weight. Intentionally tokenizer-free; the estimate is further corrected
 * at runtime by the EWMA self-calibration in AiContextBudgetService.
 */
public final class AiTokenEstimator {
    /** CJK ideographs / kana / hangul and fullwidth forms. */
    private static final double CJK_TOKEN_WEIGHT = 0.6;
    /** ASCII letters, digits, whitespace and other plain text. */
    private static final double ASCII_TOKEN_WEIGHT = 0.3;
    /** Code/JSON punctuation, symbols and remaining non-ASCII chars. */
    private static final double PUNCTUATION_TOKEN_WEIGHT = 0.5;

    private AiTokenEstimator() {
    }

    public static int estimate(String value) {
        if (value == null || value.isBlank()) {
            return 0;
        }
        double tokens = 0;
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (isCjk(c)) {
                tokens += CJK_TOKEN_WEIGHT;
            } else if (c < 128 && !isCodePunctuation(c)) {
                tokens += ASCII_TOKEN_WEIGHT;
            } else {
                tokens += PUNCTUATION_TOKEN_WEIGHT;
            }
        }
        return Math.max(1, (int) Math.ceil(tokens));
    }

    private static boolean isCjk(char c) {
        Character.UnicodeScript script = Character.UnicodeScript.of(c);
        return script == Character.UnicodeScript.HAN
                || script == Character.UnicodeScript.HIRAGANA
                || script == Character.UnicodeScript.KATAKANA
                || script == Character.UnicodeScript.HANGUL
                || (c >= 0x3000 && c <= 0x303F)
                || (c >= 0xFF00 && c <= 0xFFEF);
    }

    private static boolean isCodePunctuation(char c) {
        return switch (c) {
            case '{', '}', '[', ']', '(', ')', '<', '>', '"', '\'', '`',
                 ':', ';', ',', '.', '=', '+', '-', '*', '/', '\\', '|', '&',
                 '!', '?', '@', '#', '$', '%', '^', '~', '_' -> true;
            default -> false;
        };
    }
}
