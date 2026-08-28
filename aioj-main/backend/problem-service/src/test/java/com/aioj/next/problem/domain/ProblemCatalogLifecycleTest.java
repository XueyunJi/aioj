package com.aioj.next.problem.domain;

import com.aioj.next.common.error.DomainException;
import com.aioj.next.common.security.Role;
import com.aioj.next.common.security.SecurityPrincipal;
import com.aioj.next.contract.problem.ProblemCreateRequest;
import com.aioj.next.contract.problem.ProblemLanguageTimeLimitMultipliers;
import com.aioj.next.contract.problem.ProblemSolutionResponse;
import com.aioj.next.contract.problem.ProblemStandardSolutionPayload;
import com.aioj.next.contract.problem.ProblemTestcaseGeneratorResponse;
import com.aioj.next.contract.problem.ProblemUpdateRequest;
import com.aioj.next.contract.problem.TestCaseDto;
import com.aioj.next.contract.problem.Difficulty;
import com.aioj.next.problem.persistence.entity.ProblemEntity;
import com.aioj.next.problem.persistence.entity.ProblemSolutionEntity;
import com.aioj.next.problem.persistence.entity.ProblemTestcaseGeneratorEntity;
import com.aioj.next.problem.persistence.mapper.ProblemMapper;
import com.aioj.next.problem.persistence.mapper.ProblemSolutionMapper;
import com.aioj.next.problem.persistence.mapper.ProblemTestcaseGeneratorMapper;
import com.aioj.next.problem.persistence.mapper.ProblemTestCaseMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ProblemCatalogLifecycleTest {
    @Mock
    private ProblemMapper problemMapper;
    @Mock
    private ProblemTestCaseMapper testCaseMapper;
    @Mock
    private ProblemSolutionMapper solutionMapper;
    @Mock
    private ProblemTestcaseGeneratorMapper testcaseGeneratorMapper;
    @Mock
    private OperationAuditService auditService;
    @Mock
    private JdbcTemplate jdbcTemplate;
    @Mock
    private ContestProblemVisibilityService visibilityService;

    private ProblemCatalog catalog;

    @BeforeEach
    void setUp() {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new Configuration(), "problem-catalog-test"), ProblemEntity.class);
        org.mockito.Mockito.lenient().when(visibilityService.isStaffViewer()).thenAnswer(invocation -> {
            var authentication = SecurityContextHolder.getContext().getAuthentication();
            if (authentication == null || authentication.getAuthorities() == null) {
                return false;
            }
            return authentication.getAuthorities().stream().anyMatch(authority ->
                    "ROLE_TEACHER".equals(authority.getAuthority()) || "ROLE_ADMIN".equals(authority.getAuthority()));
        });
        org.mockito.Mockito.lenient().when(visibilityService.isPrivate(any())).thenReturn(false);
        catalog = new ProblemCatalog(problemMapper, testCaseMapper, solutionMapper, testcaseGeneratorMapper,
                new ObjectMapper(), auditService, jdbcTemplate, visibilityService);
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                new SecurityPrincipal(99L, "teacher", Set.of(Role.TEACHER)), "n/a"));
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void restoreClearsArchivedAtWithExplicitNullUpdate() {
        ProblemEntity problem = new ProblemEntity();
        problem.setId(1L);
        problem.setTitle("Archived problem");
        problem.setDifficulty(Difficulty.EASY);
        problem.setStatement("statement");
        problem.setNotes("");
        problem.setTags("[]");
        problem.setTimeLimitMillis(1000);
        problem.setMemoryLimitKb(262144);
        problem.setAiGenerated(false);
        problem.setDeleted(false);
        problem.setCreatedAt(Instant.parse("2026-06-01T00:00:00Z"));
        problem.setUpdatedAt(Instant.parse("2026-06-01T00:00:00Z"));
        problem.setArchivedAt(Instant.parse("2026-06-02T00:00:00Z"));
        when(problemMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(problem);
        when(testCaseMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of());

        var response = catalog.restore(1L);

        assertNull(response.archivedAt());
        verify(problemMapper).update(any(ProblemEntity.class), any(UpdateWrapper.class));
    }

    @Test
    void createWritesSingleStandardSolution() {
        doAnswer(invocation -> {
            ProblemEntity problem = invocation.getArgument(0);
            problem.setId(42L);
            return 1;
        }).when(problemMapper).insert(any(ProblemEntity.class));
        when(testCaseMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of());

        catalog.create(new ProblemCreateRequest(
                "Two Sum",
                Difficulty.EASY,
                "statement",
                List.of("array"),
                List.of(new TestCaseDto("1 2\n", "3\n", true)),
                1000,
                262144,
                "notes",
                "c++17",
                "int main(){return 0;}",
                null,
                null,
                null,
                null
        ), true);

        ArgumentCaptor<ProblemSolutionEntity> captor = ArgumentCaptor.forClass(ProblemSolutionEntity.class);
        verify(solutionMapper).delete(any(LambdaQueryWrapper.class));
        verify(solutionMapper).insert(captor.capture());
        assertEquals(42L, captor.getValue().getProblemId());
        assertEquals("cpp", captor.getValue().getLanguage());
        assertEquals("int main(){return 0;}", captor.getValue().getContent());
        assertEquals(99L, captor.getValue().getCreatedBy());
    }

    @Test
    void listSearchesOnlyTitleOrProblemNumberNeverStatement() {
        Page<ProblemEntity> page = new Page<>(1, 20);
        page.setRecords(List.of());
        page.setTotal(0);
        when(problemMapper.selectPage(any(), any(LambdaQueryWrapper.class))).thenReturn(page);

        catalog.list(1, 20, "207233", null, null, null, null, null);

        ArgumentCaptor<LambdaQueryWrapper<ProblemEntity>> query = ArgumentCaptor.forClass(LambdaQueryWrapper.class);
        verify(problemMapper).selectPage(any(), query.capture());
        String sql = query.getValue().getSqlSegment();
        assertEquals(true, sql.contains("title"));
        assertEquals(true, sql.contains("CAST(id AS CHAR)"));
        assertEquals(false, sql.toLowerCase().contains("statement"));
    }

    @Test
    void createWritesMultipleStandardSolutionsInCanonicalOrder() {
        doAnswer(invocation -> {
            ProblemEntity problem = invocation.getArgument(0);
            problem.setId(42L);
            return 1;
        }).when(problemMapper).insert(any(ProblemEntity.class));
        when(testCaseMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of());

        catalog.create(new ProblemCreateRequest(
                "Multi Solution",
                Difficulty.EASY,
                "statement",
                List.of("array"),
                List.of(new TestCaseDto("1 2\n", "3\n", true)),
                1000,
                262144,
                "notes",
                "python",
                "legacy should be ignored",
                List.of(
                        new ProblemStandardSolutionPayload("java", "class Main {}"),
                        new ProblemStandardSolutionPayload("cpp", "int main(){return 0;}"),
                        new ProblemStandardSolutionPayload("python", "print(1)")
                ),
                null,
                null,
                null
        ), true);

        ArgumentCaptor<ProblemSolutionEntity> captor = ArgumentCaptor.forClass(ProblemSolutionEntity.class);
        verify(solutionMapper).delete(any(LambdaQueryWrapper.class));
        verify(solutionMapper, times(3)).insert(captor.capture());
        List<ProblemSolutionEntity> rows = captor.getAllValues();
        assertEquals(List.of("cpp", "python", "java"), rows.stream().map(ProblemSolutionEntity::getLanguage).toList());
        assertEquals(List.of("int main(){return 0;}", "print(1)", "class Main {}"),
                rows.stream().map(ProblemSolutionEntity::getContent).toList());
    }

    @Test
    void updateNewStandardSolutionsDeleteBlankLanguages() {
        ProblemEntity problem = activeProblem();
        when(problemMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(problem);
        when(testCaseMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of());

        catalog.update(7L, new ProblemUpdateRequest(
                "Blank Python",
                Difficulty.MEDIUM,
                "statement",
                List.of(),
                List.of(new TestCaseDto("1\n", "1\n", true)),
                1000,
                262144,
                null,
                "python",
                "legacy should be ignored",
                List.of(
                        new ProblemStandardSolutionPayload("cpp", "int main(){return 0;}"),
                        new ProblemStandardSolutionPayload("python", "   "),
                        new ProblemStandardSolutionPayload("java", "class Main {}")
                ),
                null,
                null,
                null
        ));

        ArgumentCaptor<ProblemSolutionEntity> captor = ArgumentCaptor.forClass(ProblemSolutionEntity.class);
        verify(solutionMapper).delete(any(LambdaQueryWrapper.class));
        verify(solutionMapper, times(2)).insert(captor.capture());
        assertEquals(List.of("cpp", "java"), captor.getAllValues().stream().map(ProblemSolutionEntity::getLanguage).toList());
    }

    @Test
    void updateDeletesStandardSolutionWhenCodeIsBlank() {
        ProblemEntity problem = activeProblem();
        when(problemMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(problem);
        when(testCaseMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of());

        catalog.update(7L, new ProblemUpdateRequest(
                "No Solution",
                Difficulty.MEDIUM,
                "statement",
                List.of(),
                List.of(new TestCaseDto("1\n", "1\n", true)),
                1000,
                262144,
                null,
                "python",
                "   ",
                null,
                null,
                null,
                null
        ));

        verify(solutionMapper).delete(any(LambdaQueryWrapper.class));
        verify(solutionMapper, never()).insert(any(ProblemSolutionEntity.class));
    }

    @Test
    void createPersistsAndReturnsLanguageTimeLimitMultipliers() {
        doAnswer(invocation -> {
            ProblemEntity problem = invocation.getArgument(0);
            problem.setId(42L);
            return 1;
        }).when(problemMapper).insert(any(ProblemEntity.class));
        when(testCaseMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of());

        var response = catalog.create(new ProblemCreateRequest(
                "Multiplier",
                Difficulty.EASY,
                "statement",
                List.of("array"),
                List.of(new TestCaseDto("1 2\n", "3\n", true)),
                1000,
                262144,
                "notes",
                null,
                null,
                null,
                new ProblemLanguageTimeLimitMultipliers(new BigDecimal("1.20"), new BigDecimal("2.00"), new BigDecimal("1.50")),
                null,
                null
        ), true);

        ArgumentCaptor<ProblemEntity> captor = ArgumentCaptor.forClass(ProblemEntity.class);
        verify(problemMapper).insert(captor.capture());
        assertEquals(new BigDecimal("1.20"), captor.getValue().getCppTimeLimitMultiplier());
        assertEquals(new BigDecimal("2.00"), captor.getValue().getPythonTimeLimitMultiplier());
        assertEquals(new BigDecimal("1.50"), captor.getValue().getJavaTimeLimitMultiplier());
        assertEquals(new BigDecimal("2.00"), response.languageTimeLimitMultipliers().python());
    }

    @Test
    void createDefaultsLanguageTimeLimitMultipliers() {
        doAnswer(invocation -> {
            ProblemEntity problem = invocation.getArgument(0);
            problem.setId(42L);
            return 1;
        }).when(problemMapper).insert(any(ProblemEntity.class));
        when(testCaseMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of());

        var response = catalog.create(new ProblemCreateRequest(
                "Default Multiplier",
                Difficulty.EASY,
                "statement",
                List.of("array"),
                List.of(new TestCaseDto("1 2\n", "3\n", true)),
                1000,
                262144,
                "notes",
                null,
                null,
                null,
                null,
                null,
                null
        ), true);

        assertEquals(new BigDecimal("1.00"), response.languageTimeLimitMultipliers().cpp());
        assertEquals(new BigDecimal("1.00"), response.languageTimeLimitMultipliers().python());
        assertEquals(new BigDecimal("1.00"), response.languageTimeLimitMultipliers().java());
    }

    @Test
    void createRejectsOutOfRangeLanguageTimeLimitMultiplier() {
        assertThrows(DomainException.class, () -> catalog.create(new ProblemCreateRequest(
                "Bad Multiplier",
                Difficulty.EASY,
                "statement",
                List.of("array"),
                List.of(new TestCaseDto("1 2\n", "3\n", true)),
                1000,
                262144,
                "notes",
                null,
                null,
                null,
                new ProblemLanguageTimeLimitMultipliers(new BigDecimal("0.90"), BigDecimal.ONE, BigDecimal.ONE),
                null,
                null
        ), true));

        verify(problemMapper, never()).insert(any(ProblemEntity.class));
    }

    @Test
    void effectiveTimeLimitUsesLanguageMultipliers() {
        ProblemEntity problem = activeProblem();
        problem.setTimeLimitMillis(1001);
        problem.setCppTimeLimitMultiplier(new BigDecimal("1.00"));
        problem.setPythonTimeLimitMultiplier(new BigDecimal("2.50"));
        problem.setJavaTimeLimitMultiplier(new BigDecimal("1.20"));

        assertEquals(1001, catalog.effectiveTimeLimitMillis(problem, "cpp"));
        assertEquals(2503, catalog.effectiveTimeLimitMillis(problem, "python"));
        assertEquals(1202, catalog.effectiveTimeLimitMillis(problem, "java"));
    }

    @Test
    void createWritesTestcaseGeneratorPython() {
        doAnswer(invocation -> {
            ProblemEntity problem = invocation.getArgument(0);
            problem.setId(42L);
            return 1;
        }).when(problemMapper).insert(any(ProblemEntity.class));
        when(testCaseMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of());

        catalog.create(new ProblemCreateRequest(
                "Generated",
                Difficulty.EASY,
                "statement",
                List.of("array"),
                List.of(new TestCaseDto("1 2\n", "3\n", true)),
                1000,
                262144,
                "notes",
                "cpp",
                "int main(){return 0;}",
                null,
                null,
                "from pathlib import Path\nPath('tests').mkdir(exist_ok=True)",
                null
        ), true);

        ArgumentCaptor<ProblemTestcaseGeneratorEntity> captor = ArgumentCaptor.forClass(ProblemTestcaseGeneratorEntity.class);
        verify(testcaseGeneratorMapper).delete(any(LambdaQueryWrapper.class));
        verify(testcaseGeneratorMapper).insert(captor.capture());
        assertEquals(42L, captor.getValue().getProblemId());
        assertEquals("from pathlib import Path\nPath('tests').mkdir(exist_ok=True)", captor.getValue().getContent());
        assertEquals(99L, captor.getValue().getCreatedBy());
    }

    @Test
    void updateDeletesTestcaseGeneratorWhenCodeIsBlank() {
        ProblemEntity problem = activeProblem();
        when(problemMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(problem);
        when(testCaseMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of());

        catalog.update(7L, new ProblemUpdateRequest(
                "No Generator",
                Difficulty.MEDIUM,
                "statement",
                List.of(),
                List.of(new TestCaseDto("1\n", "1\n", true)),
                1000,
                262144,
                null,
                "python",
                "print(input())",
                null,
                null,
                "   ",
                null
        ));

        verify(testcaseGeneratorMapper).delete(any(LambdaQueryWrapper.class));
        verify(testcaseGeneratorMapper, never()).insert(any(ProblemTestcaseGeneratorEntity.class));
    }

    @Test
    void standardSolutionReturnsTeacherOnlyPayloadWithoutProblemResponseLeak() {
        ProblemEntity problem = activeProblem();
        ProblemSolutionEntity solution = new ProblemSolutionEntity();
        solution.setId(3L);
        solution.setProblemId(7L);
        solution.setLanguage("python");
        solution.setContent("print(input())");
        solution.setCreatedAt(Instant.parse("2026-06-03T00:00:00Z"));
        when(problemMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(problem);
        when(solutionMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of(solution));

        ProblemSolutionResponse response = catalog.standardSolution(7L);

        assertEquals(3L, response.id());
        assertEquals(7L, response.problemId());
        assertEquals("python", response.language());
        assertEquals("print(input())", response.content());
    }

    @Test
    void standardSolutionsReturnAllPayloadsInCanonicalOrder() {
        ProblemEntity problem = activeProblem();
        ProblemSolutionEntity java = solution(5L, "java", "class Main {}", "2026-06-03T00:00:00Z");
        ProblemSolutionEntity python = solution(4L, "python", "print(input())", "2026-06-04T00:00:00Z");
        ProblemSolutionEntity cpp = solution(3L, "cpp", "int main(){}", "2026-06-05T00:00:00Z");
        when(problemMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(problem);
        when(solutionMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of(java, python, cpp));

        List<ProblemSolutionResponse> responses = catalog.standardSolutions(7L);

        assertEquals(List.of("cpp", "python", "java"), responses.stream().map(ProblemSolutionResponse::language).toList());
        assertEquals("int main(){}", catalog.standardSolution(7L).content());
    }

    @Test
    void testcaseGeneratorReturnsTeacherOnlyPayloadWithoutProblemResponseLeak() {
        ProblemEntity problem = activeProblem();
        ProblemTestcaseGeneratorEntity generator = new ProblemTestcaseGeneratorEntity();
        generator.setId(4L);
        generator.setProblemId(7L);
        generator.setContent("print('generated')");
        generator.setCreatedAt(Instant.parse("2026-06-03T00:00:00Z"));
        generator.setUpdatedAt(Instant.parse("2026-06-04T00:00:00Z"));
        when(problemMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(problem);
        when(testcaseGeneratorMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(generator);

        ProblemTestcaseGeneratorResponse response = catalog.testcaseGenerator(7L);

        assertEquals(4L, response.id());
        assertEquals(7L, response.problemId());
        assertEquals("print('generated')", response.content());
        assertEquals(Instant.parse("2026-06-04T00:00:00Z"), response.updatedAt());
    }

    private ProblemEntity activeProblem() {
        ProblemEntity problem = new ProblemEntity();
        problem.setId(7L);
        problem.setTitle("Active problem");
        problem.setDifficulty(Difficulty.EASY);
        problem.setStatement("statement");
        problem.setNotes("");
        problem.setTags("[]");
        problem.setTimeLimitMillis(1000);
        problem.setMemoryLimitKb(262144);
        problem.setAiGenerated(false);
        problem.setDeleted(false);
        problem.setCreatedAt(Instant.parse("2026-06-01T00:00:00Z"));
        problem.setUpdatedAt(Instant.parse("2026-06-01T00:00:00Z"));
        return problem;
    }

    private ProblemSolutionEntity solution(Long id, String language, String content, String createdAt) {
        ProblemSolutionEntity solution = new ProblemSolutionEntity();
        solution.setId(id);
        solution.setProblemId(7L);
        solution.setLanguage(language);
        solution.setContent(content);
        solution.setCreatedAt(Instant.parse(createdAt));
        return solution;
    }
}
