package com.aioj.next.ai.agent.guard;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * P3-5: feature-family coverage of the L4 full-code heuristic (design doc §5.4).
 * Complete per-language solutions are caught; idea-level fragments stay allowed;
 * the min-features threshold boundary is exact.
 */
class FullCodeHeuristicDetectorTest {

    private final FullCodeHeuristicDetector detector = new FullCodeHeuristicDetector(4);

    @Test
    void detectsCompleteCppSolution() {
        String code = """
                #include <bits/stdc++.h>
                using namespace std;
                int main() {
                    int n;
                    cin >> n;
                    int sum = 0;
                    for (int i = 0; i < n; i++) {
                        if (i % 2 == 0) {
                            sum += i;
                        }
                    }
                    cout << sum << endl;
                    return 0;
                }
                """;
        FullCodeHeuristicDetector.Detection detection = detector.detect(code);

        assertThat(detection.fullCode()).isTrue();
        assertThat(detection.features()).containsExactlyInAnyOrder(
                FullCodeHeuristicDetector.Feature.ENTRY_POINT,
                FullCodeHeuristicDetector.Feature.INPUT_READING,
                FullCodeHeuristicDetector.Feature.CORE_LOGIC,
                FullCodeHeuristicDetector.Feature.OUTPUT_WRITING,
                FullCodeHeuristicDetector.Feature.COMPILABILITY);
    }

    @Test
    void detectsCompleteJavaSolution() {
        String code = """
                import java.util.Scanner;
                public class Main {
                    public static void main(String[] args) {
                        Scanner sc = new Scanner(System.in);
                        int n = sc.nextInt();
                        int sum = 0;
                        for (int i = 0; i < n; i++) {
                            if (i % 2 == 0) {
                                sum += i;
                            }
                        }
                        System.out.println(sum);
                    }
                }
                """;
        FullCodeHeuristicDetector.Detection detection = detector.detect(code);

        assertThat(detection.fullCode()).isTrue();
        assertThat(detection.features()).contains(
                FullCodeHeuristicDetector.Feature.ENTRY_POINT,
                FullCodeHeuristicDetector.Feature.INPUT_READING,
                FullCodeHeuristicDetector.Feature.CORE_LOGIC,
                FullCodeHeuristicDetector.Feature.OUTPUT_WRITING,
                FullCodeHeuristicDetector.Feature.COMPILABILITY);
    }

    @Test
    void detectsCompletePythonSolution() {
        String code = """
                import sys

                def main():
                    n = int(input())
                    total = 0
                    for x in range(n):
                        if x % 2 == 0:
                            total += x
                        while total < 0:
                            total += 1
                    print(total)

                if __name__ == '__main__':
                    main()
                """;
        FullCodeHeuristicDetector.Detection detection = detector.detect(code);

        assertThat(detection.fullCode()).isTrue();
        assertThat(detection.features()).contains(
                FullCodeHeuristicDetector.Feature.ENTRY_POINT,
                FullCodeHeuristicDetector.Feature.INPUT_READING,
                FullCodeHeuristicDetector.Feature.CORE_LOGIC,
                FullCodeHeuristicDetector.Feature.OUTPUT_WRITING,
                FullCodeHeuristicDetector.Feature.COMPILABILITY);
    }

    @Test
    void detectsFencedSolutionInMarkdownAnswer() {
        String answer = """
                可以直接这样做：
                ```cpp
                int main() {
                    int n;
                    cin >> n;
                    for (int i = 0; i < n; i++) {
                        if (n > 0) n--;
                    }
                    cout << n;
                }
                ```
                提交即可。
                """;
        assertThat(detector.detect(answer).fullCode()).isTrue();
    }

    @Test
    void ideaLevelFragmentIsNotFullCode() {
        String hint = "思路：枚举每个位置，用 `for (int i = 0; i < n; i++)` 遍历，"
                + "遇到 `if (x > 0)` 的情况就累加，最后输出答案即可。";
        FullCodeHeuristicDetector.Detection detection = detector.detect(hint);

        assertThat(detection.fullCode()).isFalse();
        assertThat(detection.features()).doesNotContain(
                FullCodeHeuristicDetector.Feature.ENTRY_POINT,
                FullCodeHeuristicDetector.Feature.INPUT_READING,
                FullCodeHeuristicDetector.Feature.OUTPUT_WRITING);
    }

    @Test
    void loopFragmentWithoutEntryOrIoIsNotFullCode() {
        String snippet = """
                for (int i = 0; i < n; i++) {
                    if (a[i] == x) {
                        found = true;
                    }
                }
                """;
        FullCodeHeuristicDetector.Detection detection = detector.detect(snippet);

        assertThat(detection.fullCode()).isFalse();
        assertThat(detection.features()).containsExactlyInAnyOrder(
                FullCodeHeuristicDetector.Feature.CORE_LOGIC,
                FullCodeHeuristicDetector.Feature.COMPILABILITY);
    }

    @Test
    void threeFeaturesStayBelowDefaultThreshold() {
        // ENTRY + CORE + OUTPUT only: no input reading, no compilability trait.
        String code = """
                def main():
                    total = 0
                    for x in xs:
                        while x > 0:
                            x -= 1
                            total += x
                    print(total)
                """;
        FullCodeHeuristicDetector.Detection detection = detector.detect(code);

        assertThat(detection.features()).hasSize(3);
        assertThat(detection.fullCode()).isFalse();
    }

    @Test
    void exactlyMinFeaturesIsFullCode() {
        // Same 3-feature draft is full code when the threshold is lowered to 3.
        String code = """
                def main():
                    total = 0
                    for x in xs:
                        while x > 0:
                            x -= 1
                            total += x
                    print(total)
                """;
        assertThat(new FullCodeHeuristicDetector(3).detect(code).fullCode()).isTrue();
        // And a 4-feature draft (adds input reading) is full at the default 4.
        String withInput = "n = int(input())\n" + code;
        assertThat(detector.detect(withInput).fullCode()).isTrue();
    }

    @Test
    void minFeaturesIsClampedToOneToFive() {
        assertThat(new FullCodeHeuristicDetector(0).detect("int main() { }").features())
                .isNotEmpty();
        // Clamped to 1: a single feature already judges full code.
        assertThat(new FullCodeHeuristicDetector(0).detect("int main() { }").fullCode()).isTrue();
        // Clamped to 5: a 4-feature draft is not enough.
        FullCodeHeuristicDetector five = new FullCodeHeuristicDetector(99);
        String fourFeatures = """
                def main():
                    n = int(input())
                    for x in range(n):
                        while x > 0:
                            x -= 1
                    print(n)
                """;
        assertThat(five.detect(fourFeatures).fullCode()).isFalse();
    }

    @Test
    void blankInputHasNoFeatures() {
        assertThat(detector.detect(null).features()).isEmpty();
        assertThat(detector.detect("  ").fullCode()).isFalse();
    }
}
