package com.aioj.next.ai.domain;

import com.aioj.next.ai.domain.problem.ProblemDraftAuditContext;
import com.aioj.next.ai.persistence.entity.ProblemDraftAuditSnapshotEntity;
import com.aioj.next.ai.persistence.mapper.ProblemDraftAuditSnapshotMapper;
import com.aioj.next.contract.ai.ProblemDraftRequest;
import com.aioj.next.contract.ai.ProblemDraftResponse;
import com.aioj.next.contract.problem.TestCaseDto;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ProblemDraftAuditSnapshotServiceTest {
    @Mock
    private ProblemDraftAuditSnapshotMapper mapper;

    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();

    @Test
    void recordDraftStoresCodeHashesInsteadOfFullSources() throws Exception {
        when(mapper.insert(any(ProblemDraftAuditSnapshotEntity.class))).thenReturn(1);
        ProblemDraftAuditSnapshotService service = new ProblemDraftAuditSnapshotService(mapper, objectMapper);

        service.recordDraft(new ProblemDraftAuditContext(77L), 7L, 123L, draftWithCode());

        ArgumentCaptor<ProblemDraftAuditSnapshotEntity> captor =
                ArgumentCaptor.forClass(ProblemDraftAuditSnapshotEntity.class);
        verify(mapper).insert(captor.capture());
        ProblemDraftAuditSnapshotEntity saved = captor.getValue();
        assertThat(saved.getJobId()).isEqualTo(77L);
        assertThat(saved.getDraftId()).isEqualTo(123L);
        assertThat(saved.getCreatorUserId()).isEqualTo(7L);
        assertThat(saved.getStage()).isEqualTo("DRAFT");

        JsonNode root = objectMapper.readTree(saved.getOutputSummaryJson());
        JsonNode standard = root.path("draft").path("standardSolutionCode");
        assertThat(standard.path("present").asBoolean()).isTrue();
        assertThat(standard.path("length").asInt()).isGreaterThan(20);
        assertThat(standard.path("sha256").asText()).hasSize(64);
        assertThat(standard.path("firstLine").asText()).isEqualTo("#include <bits/stdc++.h>");
        assertThat(saved.getOutputSummaryJson()).doesNotContain("SECRET_SECOND_LINE_SHOULD_NOT_BE_STORED");
        assertThat(saved.getOutputSummaryJson()).doesNotContain("def hidden_generator_body");
    }

    @Test
    void snapshotWriteFailureDoesNotInterruptGenerationFlow() {
        doThrow(new IllegalStateException("database unavailable"))
                .when(mapper).insert(any(ProblemDraftAuditSnapshotEntity.class));
        ProblemDraftAuditSnapshotService service = new ProblemDraftAuditSnapshotService(mapper, objectMapper);

        assertThatNoException().isThrownBy(() -> service.recordRequirement(
                new ProblemDraftAuditContext(77L),
                7L,
                123L,
                new ProblemDraftRequest(
                        "哈希,数组",
                        "MEDIUM",
                        1500,
                        null,
                        "哈希",
                        List.of("哈希", "数组"),
                        null,
                        null,
                        null,
                        null,
                        "cpp",
                        null,
                        null,
                        null,
                        12,
                        null,
                        null,
                        false,
                        false
                )
        ));
    }

    private static ProblemDraftResponse draftWithCode() {
        String standardCode = """
                #include <bits/stdc++.h>
                using namespace std; // SECRET_SECOND_LINE_SHOULD_NOT_BE_STORED
                int main(){ cout << 1 << '\\n'; }
                """;
        String referenceCode = """
                #include <bits/stdc++.h>
                int main(){ return 0; }
                """;
        String generator = """
                import pathlib
                def hidden_generator_body():
                    pathlib.Path("testcases").mkdir(exist_ok=True)
                hidden_generator_body()
                """;
        return new ProblemDraftResponse(
                123L,
                "PENDING_REVIEW",
                "Hash Array Practice",
                "MEDIUM",
                "Solve it.",
                "notes",
                "cpp",
                standardCode,
                "cpp",
                referenceCode,
                generator,
                "print('stress')",
                "core algorithm: hash",
                List.of("哈希", "数组"),
                "VALID",
                List.of(),
                List.of(new TestCaseDto("1\n", "1\n", true)),
                1000,
                262144,
                null,
                "mock-model",
                11,
                13,
                Instant.now(),
                null,
                null,
                null,
                null,
                null,
                "EXECUTION_VERIFIED",
                "{\"status\":\"PASSED\"}",
                0,
                null
        );
    }
}
