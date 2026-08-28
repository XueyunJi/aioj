package com.aioj.next.problem.domain.plagiarism;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class JPlag43PlagiarismDetectorTest {
    @Test
    void returnsEmptyResultWhenJPlagRejectsAllSubmissionsAsTooShort() {
        JPlag43PlagiarismDetector detector = new JPlag43PlagiarismDetector();

        List<PlagiarismDetector.DetectedPair> pairs = detector.detect(
                new PlagiarismDetector.DetectionGroup("cpp", List.of(
                        new PlagiarismDetector.DetectionSubmission(11L, 101L, 21L, 1001L, "int main(){}"),
                        new PlagiarismDetector.DetectionSubmission(12L, 102L, 22L, 1002L, "int main(){return 0;}")
                )),
                new PlagiarismDetector.DetectionOptions(0.80, 600)
        );

        assertThat(pairs).isEmpty();
    }

    @Test
    void returnsEmptyResultWhenNoComparisonReachesThreshold() {
        JPlag43PlagiarismDetector detector = new JPlag43PlagiarismDetector();
        String left = """
                #include <bits/stdc++.h>
                using namespace std;
                int main() {
                  int n;
                  cin >> n;
                  vector<long long> values(n);
                  for (int i = 0; i < n; ++i) cin >> values[i];
                  long long answer = 0;
                  for (long long value : values) answer += value * value;
                  cout << answer << '\\n';
                  return 0;
                }
                """;
        String right = """
                #include <bits/stdc++.h>
                using namespace std;
                int main() {
                  int rows, columns;
                  cin >> rows >> columns;
                  vector<string> board(rows);
                  for (string &line : board) cin >> line;
                  queue<pair<int, int>> pending;
                  vector<vector<int>> distance(rows, vector<int>(columns, -1));
                  if (rows > 0 && columns > 0) {
                    pending.push({0, 0});
                    distance[0][0] = 0;
                  }
                  const int dr[] = {1, -1, 0, 0};
                  const int dc[] = {0, 0, 1, -1};
                  while (!pending.empty()) {
                    auto [row, column] = pending.front();
                    pending.pop();
                    for (int direction = 0; direction < 4; ++direction) {
                      int nextRow = row + dr[direction];
                      int nextColumn = column + dc[direction];
                      if (nextRow < 0 || nextRow >= rows || nextColumn < 0 || nextColumn >= columns) continue;
                      if (board[nextRow][nextColumn] == '#' || distance[nextRow][nextColumn] >= 0) continue;
                      distance[nextRow][nextColumn] = distance[row][column] + 1;
                      pending.push({nextRow, nextColumn});
                    }
                  }
                  cout << (rows > 0 && columns > 0 ? distance[rows - 1][columns - 1] : -1) << '\\n';
                  return 0;
                }
                """;

        List<PlagiarismDetector.DetectedPair> pairs = detector.detect(
                new PlagiarismDetector.DetectionGroup("cpp", List.of(
                        new PlagiarismDetector.DetectionSubmission(11L, 101L, 21L, 1001L, left),
                        new PlagiarismDetector.DetectionSubmission(12L, 102L, 22L, 1002L, right)
                )),
                new PlagiarismDetector.DetectionOptions(0.99, 600)
        );

        assertThat(pairs).isEmpty();
    }

    @Test
    void detectsSimilarCppSubmissions() {
        JPlag43PlagiarismDetector detector = new JPlag43PlagiarismDetector();
        String left = """
                #include <bits/stdc++.h>
                using namespace std;
                int main() {
                  int n;
                  cin >> n;
                  vector<int> a(n);
                  for (int i = 0; i < n; ++i) cin >> a[i];
                  sort(a.begin(), a.end());
                  long long sum = 0;
                  for (int value : a) sum += value;
                  cout << sum << '\\n';
                  return 0;
                }
                """;
        String right = """
                #include <bits/stdc++.h>
                using namespace std;
                int main() {
                  int n;
                  cin >> n;
                  vector<int> b(n);
                  for (int i = 0; i < n; i++) cin >> b[i];
                  sort(b.begin(), b.end());
                  long long ans = 0;
                  for (int value : b) ans += value;
                  cout << ans << '\\n';
                  return 0;
                }
                """;

        List<PlagiarismDetector.DetectedPair> pairs = detector.detect(
                new PlagiarismDetector.DetectionGroup("cpp", List.of(
                        new PlagiarismDetector.DetectionSubmission(1L, 101L, 11L, 1001L, left),
                        new PlagiarismDetector.DetectionSubmission(2L, 102L, 12L, 1002L, right)
                )),
                new PlagiarismDetector.DetectionOptions(0.4, 600)
        );

        assertThat(pairs).isNotEmpty();
        assertThat(pairs.get(0).similarity()).isGreaterThanOrEqualTo(0.4);
        assertThat(pairs.get(0).fragments()).isNotEmpty();
    }
}
