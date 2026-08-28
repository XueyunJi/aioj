package com.aioj.next.ai.domain;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AiResponsePolicyGuardTest {
    private final AiResponsePolicyGuard guard = new AiResponsePolicyGuard();

    @Test
    void replacesSubmitReadyCodeDuringConstrainedTurn() {
        AiCompletion completion = new AiCompletion("""
                ```cpp
                #include <bits/stdc++.h>
                using namespace std;
                int main() {
                    ios::sync_with_stdio(false);
                    cin.tie(nullptr);
                    int n;
                    cin >> n;
                    vector<int> a(n);
                    for (int i = 0; i < n; i++) cin >> a[i];
                    sort(a.begin(), a.end());
                    long long ans = 0;
                    for (int x : a) ans += x;
                    cout << ans << '\\n';
                    return 0;
                }
                ```
                """, "test", "model", 10, 20);

        AiResponsePolicyGuard.GuardedCompletion guarded = guard.guard(7L, "conversation", completion, true);

        assertThat(guarded.replaced()).isTrue();
        assertThat(guarded.reason()).isEqualTo("SUBMIT_READY_CODE_DETECTED");
        assertThat(guarded.completion().content()).contains("不能给出完整可提交代码");
        assertThat(guarded.completion().content()).doesNotContain("#include");
        assertThat(guarded.completion().teachingDecision()).isEqualTo("DEBUG");
        assertThat(guarded.completion().provider()).isEqualTo("test");
        assertThat(guarded.completion().model()).isEqualTo("model");
    }

    @Test
    void replacesJavaSubmitReadyCodeDuringConstrainedTurn() {
        AiCompletion completion = new AiCompletion("""
                public class Main {
                    public static void main(String[] args) {
                        java.util.Scanner scanner = new java.util.Scanner(System.in);
                        int n = scanner.nextInt();
                        long ans = 0;
                        for (int i = 0; i < n; i++) {
                            ans += scanner.nextInt();
                        }
                        java.io.PrintWriter out = new java.io.PrintWriter(System.out);
                        out.println(ans);
                        out.flush();
                    }
                }
                """, "test", "model", 10, 20);

        AiResponsePolicyGuard.GuardedCompletion guarded = guard.guard(7L, "conversation", completion, true);

        assertThat(guarded.replaced()).isTrue();
        assertThat(guarded.completion().content()).doesNotContain("public class Main", "Scanner");
    }

    @Test
    void replacesPythonSubmitReadyCodeDuringConstrainedTurn() {
        AiCompletion completion = new AiCompletion("""
                import sys
                def main():
                    data = list(map(int, sys.stdin.buffer.read().split()))
                    n = data[0]
                    print(sum(data[1:1+n]))
                if __name__ == "__main__":
                    main()
                """, "test", "model", 10, 20);

        AiResponsePolicyGuard.GuardedCompletion guarded = guard.guard(7L, "conversation", completion, true);

        assertThat(guarded.replaced()).isTrue();
        assertThat(guarded.completion().content()).doesNotContain("import sys", "def main");
    }

    @Test
    void keepsGuidanceDuringConstrainedTurn() {
        AiCompletion completion = new AiCompletion("可以先检查边界条件，并构造 n=1、全相等、严格递增三类反例。", "test", "model", 10, 20);

        AiResponsePolicyGuard.GuardedCompletion guarded = guard.guard(7L, "conversation", completion, true);

        assertThat(guarded.replaced()).isFalse();
        assertThat(guarded.completion()).isSameAs(completion);
    }

    @Test
    void keepsShortPseudocodeDuringConstrainedTurn() {
        AiCompletion completion = new AiCompletion("""
                可以写成伪代码：
                1. sort positions
                2. binary search d
                3. greedily count how many points can be chosen
                """, "test", "model", 10, 20);

        AiResponsePolicyGuard.GuardedCompletion guarded = guard.guard(7L, "conversation", completion, true);

        assertThat(guarded.replaced()).isFalse();
        assertThat(guarded.completion()).isSameAs(completion);
    }

    @Test
    void keepsCodeWhenTurnIsNotConstrained() {
        AiCompletion completion = new AiCompletion("""
                ```cpp
                #include <bits/stdc++.h>
                using namespace std;
                int main() { int n; cin >> n; while (n--) cout << n << '\\n'; }
                ```
                """, "test", "model", 10, 20);

        AiResponsePolicyGuard.GuardedCompletion guarded = guard.guard(7L, "conversation", completion, false);

        assertThat(guarded.replaced()).isFalse();
        assertThat(guarded.completion()).isSameAs(completion);
    }
}
