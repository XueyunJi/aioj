package com.aioj.next.problem.domain.plagiarism;

import de.jplag.JPlag;
import de.jplag.JPlagComparison;
import de.jplag.Match;
import de.jplag.Submission;
import de.jplag.clustering.ClusteringOptions;
import de.jplag.exceptions.ExitException;
import de.jplag.exceptions.SubmissionException;
import de.jplag.options.JPlagOptions;
import de.jplag.options.SimilarityMetric;
import org.springframework.stereotype.Component;
import org.springframework.util.FileSystemUtils;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

@Component
public class JPlag43PlagiarismDetector implements PlagiarismDetector {
    @Override
    public List<DetectedPair> detect(DetectionGroup group, DetectionOptions options) {
        if (group.submissions().size() < 2) {
            return List.of();
        }
        Path root = null;
        try {
            root = Files.createTempDirectory("aioj-plagiarism-");
            Map<String, DetectionSubmission> byDirectoryName = writeSubmissions(root, group);
            JPlagOptions jplagOptions = new JPlagOptions(language(group.language()), Set.of(root.toFile()), Set.of())
                    .withSimilarityThreshold(options.minimumSimilarity())
                    .withMaximumNumberOfComparisons(JPlagOptions.SHOW_ALL_COMPARISONS)
                    .withSimilarityMetric(SimilarityMetric.AVG)
                    .withClusteringOptions(new ClusteringOptions().withEnabled(false));
            return new JPlag(jplagOptions).run().getAllComparisons().stream()
                    .filter(comparison -> comparison.similarity() >= options.minimumSimilarity())
                    .map(comparison -> toDetectedPair(comparison, byDirectoryName, options.maxExcerptChars()))
                    .filter(pair -> pair.leftSubmissionId() != null && pair.rightSubmissionId() != null)
                    .toList();
        } catch (SubmissionException ex) {
            // A contest group may contain only empty, non-compiling, or too-short
            // sources for JPlag to retain two valid submissions. That means there
            // is no comparable pair, not that the whole plagiarism job failed.
            return List.of();
        } catch (IOException | ExitException ex) {
            throw new IllegalStateException("JPlag detection failed: " + ex.getMessage(), ex);
        } finally {
            if (root != null) {
                FileSystemUtils.deleteRecursively(root.toFile());
            }
        }
    }

    private Map<String, DetectionSubmission> writeSubmissions(Path root, DetectionGroup group) throws IOException {
        String extension = extension(group.language());
        for (DetectionSubmission submission : group.submissions()) {
            Path directory = root.resolve(directoryName(submission.submissionId()));
            Files.createDirectories(directory);
            Files.writeString(directory.resolve("Main" + extension), normalizeSource(submission.code()), StandardCharsets.UTF_8);
        }
        return group.submissions().stream()
                .collect(Collectors.toMap(submission -> directoryName(submission.submissionId()), Function.identity()));
    }

    private DetectedPair toDetectedPair(JPlagComparison comparison, Map<String, DetectionSubmission> byDirectoryName,
                                        int maxExcerptChars) {
        DetectionSubmission left = byDirectoryName.get(name(comparison.firstSubmission()));
        DetectionSubmission right = byDirectoryName.get(name(comparison.secondSubmission()));
        if (left == null || right == null) {
            return new DetectedPair(null, null, 0, 0, 0, 0, List.of());
        }
        List<DetectedFragment> fragments = comparison.matches().stream()
                .sorted(Comparator.comparingInt(Match::length).reversed())
                .limit(5)
                .map(match -> new DetectedFragment(
                        0,
                        match.startOfFirst(),
                        match.startOfSecond(),
                        match.length(),
                        excerpt(left.code(), maxExcerptChars),
                        excerpt(right.code(), maxExcerptChars)
                ))
                .toList();
        return new DetectedPair(left.submissionId(), right.submissionId(), comparison.similarity(),
                comparison.maximalSimilarity(), comparison.minimalSimilarity(), comparison.getNumberOfMatchedTokens(),
                renumber(fragments));
    }

    private List<DetectedFragment> renumber(List<DetectedFragment> fragments) {
        List<DetectedFragment> numbered = new ArrayList<>(fragments.size());
        for (int i = 0; i < fragments.size(); i++) {
            DetectedFragment fragment = fragments.get(i);
            numbered.add(new DetectedFragment(i + 1, fragment.leftStartToken(), fragment.rightStartToken(),
                    fragment.tokenLength(), fragment.leftExcerpt(), fragment.rightExcerpt()));
        }
        return numbered;
    }

    private String name(Submission submission) {
        return submission == null ? "" : submission.getName();
    }

    private String directoryName(Long submissionId) {
        return "s_" + submissionId;
    }

    private de.jplag.Language language(String language) {
        String normalized = normalizeLanguage(language);
        return switch (normalized) {
            case "java" -> new de.jplag.java.Language();
            case "python" -> new de.jplag.python3.Language();
            case "cpp" -> new de.jplag.cpp.Language();
            default -> throw new IllegalArgumentException("Unsupported plagiarism language: " + language);
        };
    }

    private String extension(String language) {
        return switch (normalizeLanguage(language)) {
            case "java" -> ".java";
            case "python" -> ".py";
            case "cpp" -> ".cpp";
            default -> ".txt";
        };
    }

    private String normalizeLanguage(String language) {
        String normalized = language == null ? "" : language.trim().toLowerCase(Locale.ROOT);
        if ("py".equals(normalized) || "python3".equals(normalized)) {
            return "python";
        }
        if ("c++".equals(normalized) || "cc".equals(normalized) || "cxx".equals(normalized)) {
            return "cpp";
        }
        return normalized;
    }

    private String normalizeSource(String code) {
        return code == null ? "" : code.replace("\r\n", "\n");
    }

    private String excerpt(String code, int maxChars) {
        String normalized = normalizeSource(code).trim();
        int limit = Math.max(200, maxChars);
        if (normalized.length() <= limit) {
            return normalized;
        }
        return normalized.substring(0, limit) + "\n...";
    }
}
