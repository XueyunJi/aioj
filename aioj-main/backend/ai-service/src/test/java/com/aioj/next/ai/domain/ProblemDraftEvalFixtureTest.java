package com.aioj.next.ai.domain;

import com.aioj.next.ai.config.AiProperties;
import com.aioj.next.ai.domain.problem.ComplexityReport;
import com.aioj.next.ai.domain.problem.CrossCheckReport;
import com.aioj.next.ai.domain.problem.DraftExecutionReport;
import com.aioj.next.ai.domain.problem.DraftSandboxClient;
import com.aioj.next.ai.domain.problem.ProblemDraftAuditContext;
import com.aioj.next.ai.domain.problem.ProblemDraftStaticValidator;
import com.aioj.next.ai.domain.problem.ProblemDraftStressGeneratorResult;
import com.aioj.next.ai.domain.problem.VerificationError;
import com.aioj.next.ai.domain.problem.VerificationOptions;
import com.aioj.next.ai.domain.problem.VerificationReport;
import com.aioj.next.ai.persistence.entity.ProblemDraftEntity;
import com.aioj.next.ai.persistence.mapper.ProblemDraftMapper;
import com.aioj.next.contract.ai.AiChatRequest;
import com.aioj.next.contract.ai.ProblemDraftRequest;
import com.aioj.next.contract.ai.ProblemDraftResponse;
import com.aioj.next.contract.problem.TestCaseDto;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.InputStream;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ProblemDraftEvalFixtureTest {
    private static final String FIXTURE_PATH = "/problem-draft-eval-fixtures/problem-draft-generation-cases.json";

    @Mock
    private AiQuotaService aiQuotaService;
    @Mock
    private ProblemDraftMapper problemDraftMapper;
    @Mock
    private ProblemServiceClient problemServiceClient;
    @Mock
    private TransactionTemplate transactionTemplate;
    @Mock
    private OperationAuditWriter auditWriter;
    @Mock
    private DraftSandboxClient draftSandboxClient;
    @Mock
    private ProblemDraftAuditSnapshotService auditSnapshotService;

    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
    private final AtomicReference<EvalFixture> currentFixture = new AtomicReference<>();
    private ProblemDraftStore store;

    @BeforeEach
    void setUp() {
        AiProperties properties = new AiProperties();
        store = new ProblemDraftStore(
                new FixtureProvider(),
                aiQuotaService,
                new AiCapacityService(properties),
                problemDraftMapper,
                problemServiceClient,
                objectMapper,
                transactionTemplate,
                auditWriter,
                new ProblemDraftStaticValidator(properties),
                draftSandboxClient,
                null
        );
        store.setProblemDraftAuditSnapshotService(auditSnapshotService);
        lenient().when(problemDraftMapper.insert(any(ProblemDraftEntity.class))).thenReturn(1);
        lenient().when(draftSandboxClient.verifyDraftDetailed(any(ProblemDraftResponse.class), any(VerificationOptions.class)))
                .thenAnswer(invocation -> executionReportFor(currentFixture.get()));
    }

    @Test
    void fixedEvalFixturesRunThroughStoreWithoutRealProviderOrSandbox() throws Exception {
        List<EvalFixture> fixtures = readFixtures();

        assertThat(fixtures)
                .extracting(EvalFixture::name)
                .containsExactly(
                        "hash-array-basic",
                        "segment-tree-sorting",
                        "interval-query",
                        "shortest-path",
                        "knapsack-dp",
                        "high-rating-complexity-trap"
                );

        long jobId = 10_000L;
        for (EvalFixture fixture : fixtures) {
            currentFixture.set(fixture);
            clearInvocations(problemDraftMapper, auditSnapshotService, draftSandboxClient, aiQuotaService);

            ProblemDraftResponse response = store.generate(
                    7L,
                    fixture.request(),
                    (stage, current, total, message) -> {
                    },
                    new ProblemDraftAuditContext(jobId++)
            );

            assertThat(response.verificationStatus()).isEqualTo(fixture.expectedVerificationStatus());
            assertThat(response.generationPlan()).contains(fixture.expectedMinimumGate());
            if (!"NONE".equals(fixture.expectedRiskCategory())) {
                assertThat(response.verificationReportJson()).contains(fixture.expectedRiskCategory());
            }
            if (fixture.expectReferenceCheck()) {
                assertThat(response.referenceSolutionCode()).isNotBlank();
                assertThat(response.stressTestcaseGeneratorPython()).contains("stress_small_001.in");
            }
            if (fixture.expectReferenceReport()) {
                assertThat(response.verificationReportJson()).contains("crossCheckReport");
            }
            if (fixture.expectComplexityReport()) {
                assertThat(response.verificationReportJson()).contains("complexityReport");
            }

            verify(auditSnapshotService).recordRequirement(any(ProblemDraftAuditContext.class), eq(7L),
                    eq(response.id()), eq(fixture.request()));
            verify(auditSnapshotService).recordDraft(any(ProblemDraftAuditContext.class), eq(7L),
                    eq(response.id()), any(ProblemDraftResponse.class));
            verify(auditSnapshotService).recordVerification(any(ProblemDraftAuditContext.class), eq(7L),
                    eq(response.id()), any(ProblemDraftResponse.class), any(VerificationReport.class),
                    eq(response.verificationStatus()), any());

            ArgumentCaptor<ProblemDraftEntity> entityCaptor = ArgumentCaptor.forClass(ProblemDraftEntity.class);
            verify(problemDraftMapper).insert(entityCaptor.capture());
            assertThat(entityCaptor.getValue().getVerificationStatus()).isEqualTo(fixture.expectedVerificationStatus());
        }
    }

    private List<EvalFixture> readFixtures() throws Exception {
        try (InputStream stream = getClass().getResourceAsStream(FIXTURE_PATH)) {
            assertThat(stream).as("eval fixture resource").isNotNull();
            return objectMapper.readValue(stream, new TypeReference<>() {
            });
        }
    }

    private DraftExecutionReport executionReportFor(EvalFixture fixture) {
        VerificationReport sandbox = new VerificationReport("PASSED", List.of(), List.of());
        CrossCheckReport crossCheck = fixture.expectReferenceReport() ? CrossCheckReport.passed(10) : null;
        ComplexityReport complexity = null;
        if (fixture.expectComplexityReport()) {
            List<VerificationError> errors = "COMPLEXITY".equals(fixture.expectedRiskCategory())
                    ? List.of(new VerificationError(
                    "COMPLEXITY_BENCHMARK_TLE",
                    "standardSolutionCode exceeded benchmark budget on max adversarial case",
                    "standardSolutionCode"
            ))
                    : List.of();
            complexity = ComplexityReport.of("O(n log n)", errors.isEmpty() ? "O(n log n)" : "O(n^2)",
                    List.of(), errors, List.of());
        }
        return new DraftExecutionReport(sandbox, crossCheck, complexity);
    }

    private final class FixtureProvider implements AiProvider {
        @Override
        public AiCompletion chat(AiChatRequest request) {
            throw new UnsupportedOperationException("Eval fixture provider does not serve chat completions");
        }

        @Override
        public ProblemDraftResponse generateProblemDraft(Long id, ProblemDraftRequest request) {
            return new ProblemDraftResponse(
                    id,
                    "PENDING_REVIEW",
                    request.topic() + " practice",
                    Optional.ofNullable(request.difficulty()).orElse("MEDIUM"),
                    "给定输入数据，按照题目要求计算并输出答案。",
                    "样例覆盖基础边界，隐藏数据覆盖极限规模。",
                    Optional.ofNullable(request.standardSolutionLanguage()).orElse("cpp"),
                    """
                            #include <bits/stdc++.h>
                            using namespace std;
                            int main(){ ios::sync_with_stdio(false); cin.tie(nullptr); long long x=0; if(cin>>x) cout<<x<<"\\n"; return 0; }
                            """,
                    "cpp",
                    """
                            #include <bits/stdc++.h>
                            using namespace std;
                            int main(){ long long x=0; if(cin>>x) cout<<x<<"\\n"; return 0; }
                            """,
                    """
                            import pathlib
                            out = pathlib.Path("testcases")
                            out.mkdir(exist_ok=True)
                            for i in range(1, 4):
                                (out / f"{i:03d}.in").write_text(str(i) + "\\n", encoding="utf-8")
                            """,
                    null,
                    "PLAN_ACCEPTED\ncoreAlgorithm: " + Optional.ofNullable(request.algorithm()).orElse(request.topic())
                            + "\nexpectedTimeComplexity: O(n log n)\nboundaryCases: empty, max, duplicates\ncommonWrongApproaches: O(n^2) brute force",
                    request.tags() == null ? List.of() : request.tags(),
                    "VALID",
                    List.of(),
                    List.of(
                            new TestCaseDto("1\n", "1\n", true),
                            new TestCaseDto("2\n", "2\n", true),
                            new TestCaseDto("3\n", "3\n", true)
                    ),
                    1000,
                    262144,
                    null,
                    model(),
                    100,
                    200,
                    Instant.now(),
                    null,
                    null,
                    null,
                    null,
                    null
            );
        }

        @Override
        public ProblemDraftStressGeneratorResult generateProblemDraftStressGenerator(Long id, ProblemDraftRequest request,
                                                                                     ProblemDraftResponse draft) {
            return new ProblemDraftStressGeneratorResult(
                    """
                            import pathlib
                            out = pathlib.Path("testcases")
                            out.mkdir(exist_ok=True)
                            (out / "stress_small_001.in").write_text("1\\n", encoding="utf-8")
                            (out / "stress_small_001.out").write_text("1\\n", encoding="utf-8")
                            """,
                    model(),
                    7,
                    11
            );
        }

        @Override
        public String providerName() {
            return "fixture";
        }

        @Override
        public String model() {
            return "fixture-model";
        }
    }

    private record EvalFixture(
            String name,
            ProblemDraftRequest request,
            String expectedMinimumGate,
            String expectedRiskCategory,
            boolean expectReferenceCheck,
            boolean expectComplexityReport,
            boolean expectReferenceReport,
            String expectedVerificationStatus
    ) {
    }
}
