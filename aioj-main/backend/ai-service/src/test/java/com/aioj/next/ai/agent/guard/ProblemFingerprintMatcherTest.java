package com.aioj.next.ai.agent.guard;

import com.aioj.next.ai.agent.policy.GuardDecision;
import com.aioj.next.contract.contest.ContestAiPolicyMode;
import com.aioj.next.contract.contest.RunningContestProblemOccurrence;
import com.aioj.next.contract.contest.RunningContestProblemStatement;
import com.aioj.next.contract.problem.ProblemVisibility;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ProblemFingerprintMatcherTest {

    private static final String STATEMENT = """
            星港间距
            题目描述
            在遥远的星系中，有 n 个星港排成一条直线，第 i 个星港的坐标为 xi。
            你需要选择恰好 m 个星港建立补给站，使得任意两个相邻补给站之间的最小距离最大化。
            输入格式
            第一行两个整数 n 和 m。
            第二行 n 个整数 x1 x2 ... xn。
            输出格式
            输出一个整数，表示最大化后的最小距离。
            样例输入
            5 3
            1 2 8 4 9
            样例输出
            3
            """;

    private final ProblemFingerprintMatcher matcher = new ProblemFingerprintMatcher(0.45);

    @Test
    void verbatimStatementPasteHitsWithScoreOne() {
        GuardVerdict verdict = matcher.match("帮我看看这道题\n" + STATEMENT + "怎么做？", List.of(problem(1002L)));

        assertThat(verdict.decision()).isEqualTo(GuardDecision.CONSTRAIN);
        assertThat(verdict.maxScore()).isEqualTo(1.0);
        assertThat(verdict.matchedProblems()).anySatisfy(ref -> {
            assertThat(ref.problemId()).isEqualTo(1002L);
            assertThat(ref.contestRunId()).isEqualTo(7701L);
            assertThat(ref.visibility()).isEqualTo("PRIVATE");
        });
    }

    @Test
    void reformattedPasteStillHits() {
        String reformatted = STATEMENT.replace("\n", " ").replace("，", ",");
        GuardVerdict verdict = matcher.match(reformatted, List.of(problem(1002L)));

        assertThat(verdict.decision()).isEqualTo(GuardDecision.CONSTRAIN);
        assertThat(verdict.maxScore()).isGreaterThanOrEqualTo(0.9);
    }

    @Test
    void partialStatementPasteHitsByContainment() {
        String half = STATEMENT.substring(0, STATEMENT.length() / 2);
        GuardVerdict verdict = matcher.match("这题什么意思：" + half, List.of(problem(1002L)));

        assertThat(verdict.decision()).isEqualTo(GuardDecision.CONSTRAIN);
        assertThat(verdict.maxScore()).isBetween(0.45, 0.75);
    }

    @Test
    void unrelatedQuestionPasses() {
        GuardVerdict verdict = matcher.match("帮我讲讲快速排序的时间复杂度推导，最好举个例子", List.of(problem(1002L)));

        assertThat(verdict.decision()).isEqualTo(GuardDecision.PASS);
        assertThat(verdict.hasMatches()).isFalse();
    }

    @Test
    void synonymSwappedPasteStillHits() {
        // Bypass attempt: student swaps surface nouns before pasting; most shingles survive.
        String swapped = STATEMENT.replace("星港", "基站").replace("补给站", "信号塔")
                .replace("坐标", "位置");
        GuardVerdict verdict = matcher.match(swapped, List.of(problem(1002L)));

        assertThat(verdict.decision()).isEqualTo(GuardDecision.CONSTRAIN);
    }

    @Test
    void genuinelyDifferentProblemDoesNotHit() {
        String other = """
                括号匹配
                给定一个只包含左右括号的字符串，判断它是否是合法的括号序列。
                输入一行字符串，长度不超过一百万。
                输出 YES 或 NO。
                样例输入
                (())()
                样例输出
                YES
                使用栈结构依次处理每个字符即可，遇到左括号入栈，右括号检查栈顶并出栈。
                """;
        GuardVerdict verdict = matcher.match(other, List.of(problem(1002L)));

        assertThat(verdict.decision()).isEqualTo(GuardDecision.PASS);
        assertThat(verdict.maxScore()).isLessThan(0.45);
    }

    @Test
    void blankTextOrEmptyCandidatesPass() {
        assertThat(matcher.match("", List.of(problem(1002L))).decision()).isEqualTo(GuardDecision.PASS);
        assertThat(matcher.match(STATEMENT, List.of()).decision()).isEqualTo(GuardDecision.PASS);
        assertThat(matcher.match(null, List.of(problem(1002L))).decision()).isEqualTo(GuardDecision.PASS);
    }

    @Test
    void multipleOccurrencesProduceOneRefEach() {
        RunningContestProblemStatement shared = new RunningContestProblemStatement(
                1002L, STATEMENT, 5501L, 7701L, 99001L, ProblemVisibility.PRIVATE, ContestAiPolicyMode.DEFAULT, null,
                List.of(new RunningContestProblemOccurrence(5501L, 7701L, 99001L),
                        new RunningContestProblemOccurrence(5502L, 7702L, 88002L)));

        GuardVerdict verdict = matcher.match(STATEMENT, List.of(shared));

        assertThat(verdict.matchedProblems()).hasSize(2);
        assertThat(verdict.matchedProblems()).extracting(ref -> ref.contestRunId()).containsExactly(7701L, 7702L);
    }

    private RunningContestProblemStatement problem(long problemId) {
        return new RunningContestProblemStatement(
                problemId, STATEMENT, 5501L, 7701L, 99001L, ProblemVisibility.PRIVATE, ContestAiPolicyMode.DEFAULT, null,
                List.of(new RunningContestProblemOccurrence(5501L, 7701L, 99001L)));
    }
}
