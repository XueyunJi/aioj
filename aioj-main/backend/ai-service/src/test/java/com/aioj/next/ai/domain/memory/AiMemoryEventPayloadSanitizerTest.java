package com.aioj.next.ai.domain.memory;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class AiMemoryEventPayloadSanitizerTest {
    private final AiMemoryEventPayloadSanitizer sanitizer = new AiMemoryEventPayloadSanitizer();
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void sanitizePayloadRemovesCodeRawOutputAndSecretsButKeepsSafeKeys() throws Exception {
        Object sanitized = sanitizer.sanitizePayload(Map.of(
                "profileKey", "wrong_answer_binary_search",
                "codeText", "int main(){return 0;}",
                "stdoutExcerpt", "hidden stdout",
                "nested", Map.of(
                        "apiKey", "sk-test-secret",
                        "note", """
                                二分边界需要复盘。
                                stdout:
                                raw output secret

                                ```cpp
                                #include <bits/stdc++.h>
                                int main(){return 0;}
                                ```
                                token=plain-token-123
                                """
                )
        ));

        String json = objectMapper.writeValueAsString(sanitized);

        assertThat(json)
                .contains("wrong_answer_binary_search")
                .contains(AiMemoryEventPayloadSanitizer.OMITTED)
                .contains("[raw output omitted]")
                .contains("[code block omitted]")
                .doesNotContain("int main")
                .doesNotContain("#include")
                .doesNotContain("hidden stdout")
                .doesNotContain("raw output secret")
                .doesNotContain("sk-test-secret")
                .doesNotContain("plain-token-123");
    }

    @Test
    void sanitizeErrorSummaryTruncatesAndRemovesUnsafeText() {
        String unsafe = """
                provider failed
                stderr:
                stack with secret

                apiKey=sk-live-secret
                public static void main(String[] args) {}
                """;

        String summary = sanitizer.sanitizeErrorSummary(unsafe.repeat(80));

        assertThat(summary)
                .contains("provider failed")
                .contains("[raw output omitted]")
                .doesNotContain("stack with secret")
                .doesNotContain("sk-live-secret")
                .doesNotContain("public static void main")
                .hasSizeLessThanOrEqualTo(AiMemoryEventPayloadSanitizer.MAX_ERROR_LENGTH);
    }
}
