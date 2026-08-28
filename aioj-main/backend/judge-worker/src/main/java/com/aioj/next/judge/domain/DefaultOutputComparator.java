package com.aioj.next.judge.domain;

import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.regex.Pattern;

@Component
public class DefaultOutputComparator implements OutputComparator {
    private static final Pattern TRAILING_WS = Pattern.compile("[\\t ]+$", Pattern.MULTILINE);

    @Override
    public ComparisonResult compare(InputStream expectedOutput, InputStream actualOutput) throws IOException {
        if (expectedOutput == null && actualOutput == null) {
            return new ComparisonResult(true, "accepted");
        }
        if (expectedOutput == null || actualOutput == null) {
            return new ComparisonResult(false, "one side is null");
        }
        String expected = normalize(new String(expectedOutput.readAllBytes(), StandardCharsets.UTF_8));
        String actual = normalize(new String(actualOutput.readAllBytes(), StandardCharsets.UTF_8));
        if (actual.equals(expected)) {
            return new ComparisonResult(true, "accepted");
        }
        return new ComparisonResult(false, "stdout does not match expected output");
    }

    private static String normalize(String text) {
        String unified = text.replace("\r\n", "\n").replace("\r", "\n");
        String trimmed = TRAILING_WS.matcher(unified).replaceAll("");
        int end = trimmed.length();
        while (end > 0 && trimmed.charAt(end - 1) == '\n') {
            end--;
        }
        return trimmed.substring(0, end);
    }
}
