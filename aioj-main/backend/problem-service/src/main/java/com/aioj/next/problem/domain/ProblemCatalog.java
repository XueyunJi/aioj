package com.aioj.next.problem.domain;

import com.aioj.next.common.api.PageResponse;
import com.aioj.next.common.error.DomainException;
import com.aioj.next.common.error.ErrorCode;
import com.aioj.next.common.security.Role;
import com.aioj.next.common.security.SecuritySupport;
import com.aioj.next.contract.problem.Difficulty;
import com.aioj.next.contract.ai.AiProblemContextRequest;
import com.aioj.next.contract.ai.AiProblemContextResponse;
import com.aioj.next.contract.problem.ProblemCreateRequest;
import com.aioj.next.contract.problem.ProblemLanguageTimeLimitMultipliers;
import com.aioj.next.contract.problem.ProblemResponse;
import com.aioj.next.contract.problem.ProblemSolutionResponse;
import com.aioj.next.contract.problem.ProblemStandardSolutionPayload;
import com.aioj.next.contract.problem.ProblemTestcaseGeneratorResponse;
import com.aioj.next.contract.problem.ProblemUpdateRequest;
import com.aioj.next.contract.problem.ProblemVisibility;
import com.aioj.next.contract.problem.TestCaseDto;
import com.aioj.next.problem.persistence.entity.ProblemEntity;
import com.aioj.next.problem.persistence.entity.ProblemSolutionEntity;
import com.aioj.next.problem.persistence.entity.ProblemTestcaseGeneratorEntity;
import com.aioj.next.problem.persistence.entity.ProblemTestCaseEntity;
import com.aioj.next.problem.persistence.mapper.ProblemMapper;
import com.aioj.next.problem.persistence.mapper.ProblemSolutionMapper;
import com.aioj.next.problem.persistence.mapper.ProblemTestCaseMapper;
import com.aioj.next.problem.persistence.mapper.ProblemTestcaseGeneratorMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

@Service
public class ProblemCatalog {
    private static final int DEFAULT_TIME_LIMIT_MILLIS = 1000;
    private static final int DEFAULT_MEMORY_LIMIT_KB = 262144;
    private static final BigDecimal DEFAULT_TIME_LIMIT_MULTIPLIER = BigDecimal.ONE.setScale(2);
    private static final BigDecimal MIN_TIME_LIMIT_MULTIPLIER = BigDecimal.ONE;
    private static final BigDecimal MAX_TIME_LIMIT_MULTIPLIER = BigDecimal.TEN;
    private static final List<String> STANDARD_SOLUTION_LANGUAGES = List.of("cpp", "python", "java");
    private static final Set<String> STANDARD_SOLUTION_LANGUAGE_SET = Set.copyOf(STANDARD_SOLUTION_LANGUAGES);
    private static final TypeReference<List<String>> STRING_LIST = new TypeReference<>() {
    };

    private final ProblemMapper problemMapper;
    private final ProblemTestCaseMapper testCaseMapper;
    private final ProblemSolutionMapper solutionMapper;
    private final ProblemTestcaseGeneratorMapper testcaseGeneratorMapper;
    private final ObjectMapper objectMapper;
    private final OperationAuditService auditService;
    private final JdbcTemplate jdbcTemplate;
    private final ContestProblemVisibilityService visibilityService;

    public ProblemCatalog(ProblemMapper problemMapper, ProblemTestCaseMapper testCaseMapper,
                          ProblemSolutionMapper solutionMapper, ProblemTestcaseGeneratorMapper testcaseGeneratorMapper,
                          ObjectMapper objectMapper, OperationAuditService auditService, JdbcTemplate jdbcTemplate,
                          ContestProblemVisibilityService visibilityService) {
        this.problemMapper = problemMapper;
        this.testCaseMapper = testCaseMapper;
        this.solutionMapper = solutionMapper;
        this.testcaseGeneratorMapper = testcaseGeneratorMapper;
        this.objectMapper = objectMapper;
        this.auditService = auditService;
        this.jdbcTemplate = jdbcTemplate;
        this.visibilityService = visibilityService;
    }

    public PageResponse<ProblemResponse> list(long page, long pageSize, String keyword, Difficulty difficulty,
                                              String tag, String status, String visibility, String sort) {
        LambdaQueryWrapper<ProblemEntity> query = applyLifecycleFilter(visibleProblemQuery(), canBrowseArchived() ? status : null);
        // Staff callers only bypass the PUBLIC filter when they explicitly ask for another
        // visibility (admin console passes ALL/PRIVATE); everyone else always sees PUBLIC.
        String effectiveVisibility = visibilityService.isStaffViewer() && StringUtils.hasText(visibility)
                ? visibility
                : "PUBLIC";
        query = applyVisibilityFilter(query, effectiveVisibility);
        String normalizedKeyword = keyword == null ? "" : keyword.trim();
        if (StringUtils.hasText(normalizedKeyword)) {
            String numericLike = "%" + normalizedKeyword + "%";
            // Student catalog search is deliberately limited to title and public
            // problem number. Do not index or expose statement text through this path.
            query.and(nested -> nested
                    .like(ProblemEntity::getTitle, normalizedKeyword)
                    .or()
                    .apply("CAST(id AS CHAR) LIKE {0}", numericLike));
        }
        Page<ProblemEntity> result = problemMapper.selectPage(new Page<>(normalizePage(page), normalizePageSize(pageSize)),
                applySort(query
                        .eq(difficulty != null, ProblemEntity::getDifficulty, difficulty)
                        .apply(StringUtils.hasText(tag), "JSON_CONTAINS(tags, JSON_QUOTE({0}))", normalizeTag(tag)), sort));
        List<ProblemResponse> records = result.getRecords().stream().map(this::toResponse).toList();
        return new PageResponse<>(records, result.getTotal(), result.getCurrent(), result.getSize());
    }

    private LambdaQueryWrapper<ProblemEntity> applySort(LambdaQueryWrapper<ProblemEntity> query, String sort) {
        if ("OLDEST".equalsIgnoreCase(sort)) {
            return query.orderByAsc(ProblemEntity::getCreatedAt).orderByAsc(ProblemEntity::getId);
        }
        if ("DIFFICULTY_ASC".equalsIgnoreCase(sort) || "DIFFICULTY_DESC".equalsIgnoreCase(sort)) {
            String direction = "DIFFICULTY_ASC".equalsIgnoreCase(sort) ? "ASC" : "DESC";
            return query.last("ORDER BY CASE difficulty WHEN 'EASY' THEN 0 WHEN 'MEDIUM' THEN 1 WHEN 'HARD' THEN 2 ELSE 3 END "
                    + direction + ", created_at DESC, id DESC");
        }
        return query.orderByDesc(ProblemEntity::getCreatedAt).orderByDesc(ProblemEntity::getId);
    }

    public ProblemResponse get(Long id) {
        return get(id, null, null, false);
    }

    /**
     * Public read path. PRIVATE problems answer 404 unless the caller explicitly uses the
     * admin staff view, or is an active participant inside an active run window that uses
     * the problem. The staff bypass requires the explicit flag so student-facing surfaces
     * behave identically for every identity.
     */
    public ProblemResponse get(Long id, Long contestRunId, Long contestProblemId, boolean staffView) {
        ProblemEntity problem = requireActiveProblem(id);
        boolean adminStaffView = staffView && visibilityService.isStaffViewer();
        if (visibilityService.isPrivate(problem) && !adminStaffView) {
            Long userId = optionalCurrentUserId();
            boolean allowed = contestRunId != null && contestProblemId != null
                    && visibilityService.canViewPrivateProblem(userId, contestRunId, contestProblemId, problem.getId(), Instant.now());
            if (!allowed) {
                throw new DomainException(ErrorCode.NOT_FOUND, "Problem not found");
            }
        }
        return toResponse(problem);
    }

    public AiProblemContextResponse aiProblemContext(AiProblemContextRequest request) {
        Long problemId = request == null ? null : request.problemId();
        if (problemId == null) {
            throw new DomainException(ErrorCode.BAD_REQUEST, "Problem id is required");
        }
        ProblemEntity problem = requireActiveProblem(problemId);
        ProblemResponse response = toResponse(problem);
        return new AiProblemContextResponse(
                problem.getId(),
                request.contestId(),
                request.contestRunId(),
                request.contestProblemId(),
                problem.getTitle(),
                problem.getDifficulty() == null ? null : problem.getDifficulty().name(),
                problem.getStatement(),
                summarizeStatement(problem.getStatement()),
                fromJson(problem.getTags()),
                inferConstraints(problem.getStatement()),
                response.samples(),
                problem.getTimeLimitMillis(),
                problem.getMemoryLimitKb(),
                request.contestRunId() == null ? "PROBLEM" : "CONTEST_PROBLEM",
                problem.getUpdatedAt()
        );
    }

    public boolean existsActive(Long id) {
        return problemMapper.selectCount(activeProblemQuery().eq(ProblemEntity::getId, id)) > 0;
    }

    public Optional<ProblemEntity> findActive(Long id) {
        return Optional.ofNullable(problemMapper.selectOne(activeProblemQuery().eq(ProblemEntity::getId, id)));
    }

    /** Internal helper for other services (e.g. AI usage records) that need titles regardless of visibility. */
    public List<com.aioj.next.contract.ai.ProblemTitleInfo> problemTitles(List<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            return List.of();
        }
        return problemMapper.selectList(new LambdaQueryWrapper<ProblemEntity>()
                        .in(ProblemEntity::getId, ids.stream().distinct().toList()))
                .stream()
                .map(problem -> new com.aioj.next.contract.ai.ProblemTitleInfo(
                        problem.getId(),
                        problem.getTitle(),
                        problem.getVisibility() == null ? ProblemVisibility.PUBLIC : problem.getVisibility()))
                .toList();
    }

    @Transactional
    public ProblemResponse create(ProblemCreateRequest request, boolean aiGenerated) {
        Instant now = Instant.now();
        ProblemEntity problem = new ProblemEntity();
        apply(problem, request.title(), request.difficulty(), request.statement(), request.notes(), request.tags(),
                request.timeLimitMillis(), request.memoryLimitKb(), request.languageTimeLimitMultipliers());
        problem.setAiGenerated(aiGenerated);
        problem.setVisibility(request.visibility() == null ? ProblemVisibility.PUBLIC : request.visibility());
        problem.setCreatedBy(SecuritySupport.currentUserId());
        problem.setCreatedAt(now);
        problem.setUpdatedAt(now);
        problem.setDeleted(false);
        problemMapper.insert(problem);
        replaceTestCases(problem.getId(), request.testCases());
        replaceStandardSolutions(problem.getId(), request.standardSolutions(), request.standardSolutionLanguage(),
                request.standardSolutionCode(), now);
        replaceTestcaseGenerator(problem.getId(), request.testcaseGeneratorPython(), now);
        return toResponse(problem);
    }

    @Transactional
    public ProblemResponse update(Long id, ProblemUpdateRequest request) {
        ProblemEntity problem = requireActiveProblem(id);
        apply(problem, request.title(), request.difficulty(), request.statement(), request.notes(), request.tags(),
                request.timeLimitMillis(), request.memoryLimitKb(), request.languageTimeLimitMultipliers());
        if (request.visibility() != null) {
            problem.setVisibility(request.visibility());
        } else if (problem.getVisibility() == null) {
            problem.setVisibility(ProblemVisibility.PUBLIC);
        }
        problem.setUpdatedAt(Instant.now());
        problemMapper.updateById(problem);
        replaceTestCases(problem.getId(), request.testCases());
        replaceStandardSolutions(problem.getId(), request.standardSolutions(), request.standardSolutionLanguage(),
                request.standardSolutionCode(), problem.getUpdatedAt());
        replaceTestcaseGenerator(problem.getId(), request.testcaseGeneratorPython(), problem.getUpdatedAt());
        return toResponse(problem);
    }

    public ProblemSolutionResponse standardSolution(Long id) {
        List<ProblemSolutionResponse> solutions = standardSolutions(id);
        return solutions.isEmpty() ? null : solutions.get(0);
    }

    public List<ProblemSolutionResponse> standardSolutions(Long id) {
        requireActiveProblem(id);
        return latestSolutionsByLanguage(id).values().stream()
                .map(this::toSolutionResponse)
                .toList();
    }

    public int effectiveTimeLimitMillis(ProblemEntity problem, String language) {
        int base = problem.getTimeLimitMillis() == null || problem.getTimeLimitMillis() <= 0
                ? DEFAULT_TIME_LIMIT_MILLIS
                : problem.getTimeLimitMillis();
        BigDecimal multiplier = timeLimitMultiplier(problem, language);
        return BigDecimal.valueOf(base)
                .multiply(multiplier)
                .setScale(0, RoundingMode.CEILING)
                .intValue();
    }

    public ProblemTestcaseGeneratorResponse testcaseGenerator(Long id) {
        requireActiveProblem(id);
        ProblemTestcaseGeneratorEntity generator = testcaseGeneratorMapper.selectOne(new LambdaQueryWrapper<ProblemTestcaseGeneratorEntity>()
                .eq(ProblemTestcaseGeneratorEntity::getProblemId, id)
                .orderByDesc(ProblemTestcaseGeneratorEntity::getUpdatedAt)
                .orderByDesc(ProblemTestcaseGeneratorEntity::getId)
                .last("LIMIT 1"));
        return generator == null ? null : toTestcaseGeneratorResponse(generator);
    }

    @Transactional
    public void delete(Long id) {
        ProblemEntity problem = requireVisibleProblem(id);
        if (problem.getArchivedAt() == null) {
            throw new DomainException(ErrorCode.BAD_REQUEST, "Only archived problems can be deleted");
        }
        assertNotReferencedByActiveContest(problem.getId());
        problem.setDeleted(true);
        problem.setDeletedAt(Instant.now());
        problem.setDeletedBy(SecuritySupport.currentUserId());
        problem.setUpdatedAt(Instant.now());
        problemMapper.updateById(problem);
        auditService.recordCurrentUser("SOFT_DELETE", "PROBLEM", problem.getId(), null, null, null,
                "SUCCESS", Map.of("title", problem.getTitle()));
    }

    @Transactional
    public ProblemResponse archive(Long id) {
        ProblemEntity problem = requireActiveProblem(id);
        problem.setArchivedAt(Instant.now());
        problem.setUpdatedAt(Instant.now());
        problemMapper.updateById(problem);
        auditService.recordCurrentUser("ARCHIVE", "PROBLEM", problem.getId(), null, null, null,
                "SUCCESS", Map.of("title", problem.getTitle()));
        return toResponse(problem);
    }

    @Transactional
    public ProblemResponse restore(Long id) {
        ProblemEntity problem = requireVisibleProblem(id);
        if (problem.getArchivedAt() == null) {
            throw new DomainException(ErrorCode.BAD_REQUEST, "Only archived problems can be restored");
        }
        Instant now = Instant.now();
        problemMapper.update(new ProblemEntity(), new UpdateWrapper<ProblemEntity>()
                .eq("id", problem.getId())
                .set("archived_at", null)
                .set("updated_at", now));
        problem.setArchivedAt(null);
        problem.setUpdatedAt(now);
        auditService.recordCurrentUser("RESTORE", "PROBLEM", problem.getId(), null, null, null,
                "SUCCESS", Map.of("title", problem.getTitle()));
        return toResponse(problem);
    }

    private ProblemEntity requireActiveProblem(Long id) {
        return findActive(id)
                .orElseThrow(() -> new DomainException(ErrorCode.NOT_FOUND, "Problem not found"));
    }

    private ProblemEntity requireVisibleProblem(Long id) {
        ProblemEntity problem = problemMapper.selectOne(visibleProblemQuery().eq(ProblemEntity::getId, id));
        if (problem == null) {
            throw new DomainException(ErrorCode.NOT_FOUND, "Problem not found");
        }
        return problem;
    }

    private LambdaQueryWrapper<ProblemEntity> activeProblemQuery() {
        return visibleProblemQuery().isNull(ProblemEntity::getArchivedAt);
    }

    private LambdaQueryWrapper<ProblemEntity> visibleProblemQuery() {
        return new LambdaQueryWrapper<ProblemEntity>()
                .eq(ProblemEntity::getDeleted, false)
                .isNull(ProblemEntity::getDeletedAt);
    }

    private LambdaQueryWrapper<ProblemEntity> applyLifecycleFilter(LambdaQueryWrapper<ProblemEntity> query,
                                                                  String status) {
        if ("ARCHIVED".equalsIgnoreCase(status)) {
            return query.isNotNull(ProblemEntity::getArchivedAt);
        }
        if ("ALL".equalsIgnoreCase(status)) {
            return query;
        }
        return query.isNull(ProblemEntity::getArchivedAt);
    }

    /**
     * The public catalog endpoint is anonymous-accessible, so archived-problem
     * visibility is gated here instead of relying on SecuritySupport (which would
     * reject anonymous callers). Non-staff callers silently get the active-only view.
     */
    private boolean canBrowseArchived() {
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || authentication.getAuthorities() == null) {
            return false;
        }
        String teacher = "ROLE_" + Role.TEACHER.name();
        String admin = "ROLE_" + Role.ADMIN.name();
        return authentication.getAuthorities().stream()
                .anyMatch(authority -> teacher.equals(authority.getAuthority()) || admin.equals(authority.getAuthority()));
    }

    private LambdaQueryWrapper<ProblemEntity> applyVisibilityFilter(LambdaQueryWrapper<ProblemEntity> query,
                                                                    String visibility) {
        if ("PRIVATE".equalsIgnoreCase(visibility)) {
            return query.eq(ProblemEntity::getVisibility, ProblemVisibility.PRIVATE);
        }
        if ("ALL".equalsIgnoreCase(visibility)) {
            return query;
        }
        return query.eq(ProblemEntity::getVisibility, ProblemVisibility.PUBLIC);
    }

    private Long optionalCurrentUserId() {
        var authentication = org.springframework.security.core.context.SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null && authentication.getPrincipal() instanceof com.aioj.next.common.security.SecurityPrincipal principal) {
            return principal.userId();
        }
        return null;
    }

    private void apply(ProblemEntity problem, String title, Difficulty difficulty, String statement, String notes,
                       List<String> tags, int timeLimitMillis, int memoryLimitKb,
                       ProblemLanguageTimeLimitMultipliers multipliers) {
        problem.setTitle(title);
        problem.setDifficulty(difficulty);
        problem.setStatement(statement);
        problem.setNotes(normalizeNotes(notes));
        problem.setTags(toJson(tags == null ? List.of() : tags));
        problem.setTimeLimitMillis(timeLimitMillis <= 0 ? DEFAULT_TIME_LIMIT_MILLIS : timeLimitMillis);
        applyTimeLimitMultipliers(problem, multipliers);
        problem.setMemoryLimitKb(memoryLimitKb <= 0 ? DEFAULT_MEMORY_LIMIT_KB : memoryLimitKb);
    }

    private void replaceTestCases(Long problemId, List<TestCaseDto> testCases) {
        testCaseMapper.delete(new LambdaQueryWrapper<ProblemTestCaseEntity>()
                .eq(ProblemTestCaseEntity::getProblemId, problemId));
        for (int i = 0; i < testCases.size(); i++) {
            TestCaseDto testCase = testCases.get(i);
            ProblemTestCaseEntity entity = new ProblemTestCaseEntity();
            entity.setProblemId(problemId);
            entity.setInput(testCase.input());
            entity.setExpectedOutput(testCase.expectedOutput());
            entity.setSample(testCase.sample());
            entity.setSortOrder(i);
            testCaseMapper.insert(entity);
        }
    }

    private void replaceStandardSolutions(Long problemId, List<ProblemStandardSolutionPayload> standardSolutions,
                                          String legacyLanguage, String legacyContent, Instant now) {
        solutionMapper.delete(new LambdaQueryWrapper<ProblemSolutionEntity>()
                .eq(ProblemSolutionEntity::getProblemId, problemId));
        if (standardSolutions != null) {
            insertStandardSolutions(problemId, standardSolutions, now);
            return;
        }
        if (!StringUtils.hasText(legacyContent)) {
            return;
        }
        insertStandardSolution(problemId, normalizeSolutionLanguage(legacyLanguage), legacyContent, now);
    }

    private void insertStandardSolutions(Long problemId, List<ProblemStandardSolutionPayload> standardSolutions,
                                         Instant now) {
        if (standardSolutions.size() > STANDARD_SOLUTION_LANGUAGES.size()) {
            throw new DomainException(ErrorCode.BAD_REQUEST, "At most three standard solutions are supported");
        }
        Map<String, String> byLanguage = new LinkedHashMap<>();
        for (ProblemStandardSolutionPayload payload : standardSolutions) {
            if (payload == null) {
                continue;
            }
            String language = normalizeSolutionLanguage(payload.language());
            if (!STANDARD_SOLUTION_LANGUAGE_SET.contains(language)) {
                throw new DomainException(ErrorCode.BAD_REQUEST, "Unsupported standard solution language: " + payload.language());
            }
            if (byLanguage.containsKey(language)) {
                throw new DomainException(ErrorCode.BAD_REQUEST, "Duplicate standard solution language: " + language);
            }
            byLanguage.put(language, payload.code());
        }
        for (String language : STANDARD_SOLUTION_LANGUAGES) {
            String content = byLanguage.get(language);
            if (StringUtils.hasText(content)) {
                insertStandardSolution(problemId, language, content, now);
            }
        }
    }

    private void insertStandardSolution(Long problemId, String language, String content, Instant now) {
        ProblemSolutionEntity solution = new ProblemSolutionEntity();
        solution.setProblemId(problemId);
        solution.setLanguage(language);
        solution.setContent(content.trim());
        solution.setCreatedBy(SecuritySupport.currentUserId());
        solution.setCreatedAt(now);
        solution.setUpdatedAt(now);
        solutionMapper.insert(solution);
    }

    private void replaceTestcaseGenerator(Long problemId, String content, Instant now) {
        testcaseGeneratorMapper.delete(new LambdaQueryWrapper<ProblemTestcaseGeneratorEntity>()
                .eq(ProblemTestcaseGeneratorEntity::getProblemId, problemId));
        if (!StringUtils.hasText(content)) {
            return;
        }
        ProblemTestcaseGeneratorEntity generator = new ProblemTestcaseGeneratorEntity();
        generator.setProblemId(problemId);
        generator.setContent(content.trim());
        generator.setCreatedBy(SecuritySupport.currentUserId());
        generator.setCreatedAt(now);
        generator.setUpdatedAt(now);
        testcaseGeneratorMapper.insert(generator);
    }

    private ProblemResponse toResponse(ProblemEntity problem) {
        List<TestCaseDto> samples = testCaseMapper.selectList(new LambdaQueryWrapper<ProblemTestCaseEntity>()
                        .eq(ProblemTestCaseEntity::getProblemId, problem.getId())
                        .eq(ProblemTestCaseEntity::getSample, true)
                        .orderByAsc(ProblemTestCaseEntity::getSortOrder)
                        .orderByAsc(ProblemTestCaseEntity::getId))
                .stream()
                .map(testCase -> new TestCaseDto(testCase.getInput(), testCase.getExpectedOutput(),
                        Boolean.TRUE.equals(testCase.getSample())))
                .toList();
        return new ProblemResponse(problem.getId(), problem.getTitle(), problem.getDifficulty(), problem.getStatement(),
                problem.getNotes(), fromJson(problem.getTags()), samples, problem.getTimeLimitMillis(),
                toMultiplierResponse(problem), problem.getMemoryLimitKb(),
                Boolean.TRUE.equals(problem.getAiGenerated()),
                problem.getVisibility() == null ? ProblemVisibility.PUBLIC : problem.getVisibility(),
                problem.getCreatedAt(), problem.getArchivedAt(),
                problem.getDeletedAt(), problem.getDeletedBy());
    }

    private ProblemSolutionResponse toSolutionResponse(ProblemSolutionEntity solution) {
        return new ProblemSolutionResponse(solution.getId(), solution.getProblemId(), solution.getLanguage(),
                solution.getContent(), solution.getCreatedAt());
    }

    private ProblemTestcaseGeneratorResponse toTestcaseGeneratorResponse(ProblemTestcaseGeneratorEntity generator) {
        return new ProblemTestcaseGeneratorResponse(generator.getId(), generator.getProblemId(), generator.getContent(),
                generator.getCreatedAt(), generator.getUpdatedAt());
    }

    private Map<String, ProblemSolutionEntity> latestSolutionsByLanguage(Long problemId) {
        List<ProblemSolutionEntity> rows = solutionMapper.selectList(new LambdaQueryWrapper<ProblemSolutionEntity>()
                .eq(ProblemSolutionEntity::getProblemId, problemId)
                .orderByDesc(ProblemSolutionEntity::getUpdatedAt)
                .orderByDesc(ProblemSolutionEntity::getId));
        Map<String, ProblemSolutionEntity> latest = new LinkedHashMap<>();
        for (ProblemSolutionEntity row : rows) {
            String language = normalizeSolutionLanguage(row.getLanguage());
            if (STANDARD_SOLUTION_LANGUAGE_SET.contains(language) && !latest.containsKey(language)) {
                latest.put(language, row);
            }
        }
        Map<String, ProblemSolutionEntity> ordered = new LinkedHashMap<>();
        for (String language : STANDARD_SOLUTION_LANGUAGES) {
            if (latest.containsKey(language)) {
                ordered.put(language, latest.get(language));
            }
        }
        return ordered;
    }

    private void applyTimeLimitMultipliers(ProblemEntity problem, ProblemLanguageTimeLimitMultipliers multipliers) {
        problem.setCppTimeLimitMultiplier(normalizeMultiplier(multipliers == null ? null : multipliers.cpp()));
        problem.setPythonTimeLimitMultiplier(normalizeMultiplier(multipliers == null ? null : multipliers.python()));
        problem.setJavaTimeLimitMultiplier(normalizeMultiplier(multipliers == null ? null : multipliers.java()));
    }

    private BigDecimal normalizeMultiplier(BigDecimal value) {
        if (value == null) {
            return DEFAULT_TIME_LIMIT_MULTIPLIER;
        }
        if (value.compareTo(MIN_TIME_LIMIT_MULTIPLIER) < 0 || value.compareTo(MAX_TIME_LIMIT_MULTIPLIER) > 0) {
            throw new DomainException(ErrorCode.BAD_REQUEST, "Language time limit multiplier must be between 1.0 and 10.0");
        }
        return value.setScale(2, RoundingMode.HALF_UP);
    }

    private ProblemLanguageTimeLimitMultipliers toMultiplierResponse(ProblemEntity problem) {
        return new ProblemLanguageTimeLimitMultipliers(
                normalizeMultiplier(problem.getCppTimeLimitMultiplier()),
                normalizeMultiplier(problem.getPythonTimeLimitMultiplier()),
                normalizeMultiplier(problem.getJavaTimeLimitMultiplier())
        );
    }

    private BigDecimal timeLimitMultiplier(ProblemEntity problem, String language) {
        String normalized = normalizeSolutionLanguage(language);
        return switch (normalized) {
            case "python" -> normalizeMultiplier(problem.getPythonTimeLimitMultiplier());
            case "java" -> normalizeMultiplier(problem.getJavaTimeLimitMultiplier());
            default -> normalizeMultiplier(problem.getCppTimeLimitMultiplier());
        };
    }

    private void assertNotReferencedByActiveContest(Long problemId) {
        Long count = jdbcTemplate.queryForObject("""
                SELECT COUNT(*)
                FROM contest_problems cp
                JOIN contests c ON c.id = cp.contest_id
                WHERE cp.problem_id = ?
                  AND c.deleted_at IS NULL
                  AND c.archived_at IS NULL
                """, Long.class, problemId);
        if (count != null && count > 0) {
            throw new DomainException(ErrorCode.CONFLICT, "Problem is referenced by an active contest blueprint");
        }
    }

    private String normalizeNotes(String notes) {
        return StringUtils.hasText(notes) ? notes.trim() : null;
    }

    private String toJson(List<String> tags) {
        try {
            return objectMapper.writeValueAsString(tags);
        } catch (JsonProcessingException ex) {
            throw new DomainException(ErrorCode.BAD_REQUEST, "Invalid problem tags");
        }
    }

    private List<String> fromJson(String tags) {
        if (!StringUtils.hasText(tags)) {
            return List.of();
        }
        try {
            return objectMapper.readValue(tags, STRING_LIST);
        } catch (JsonProcessingException ex) {
            throw new DomainException(ErrorCode.INTERNAL_ERROR, "Problem tags are not valid JSON");
        }
    }

    private String summarizeStatement(String statement) {
        if (!StringUtils.hasText(statement)) {
            return "";
        }
        String normalized = statement.replaceAll("\\s+", " ").trim();
        return normalized.length() <= 420 ? normalized : normalized.substring(0, 420);
    }

    private List<String> inferConstraints(String statement) {
        if (!StringUtils.hasText(statement)) {
            return List.of();
        }
        return statement.lines()
                .map(String::trim)
                .filter(line -> !line.isBlank())
                .filter(line -> line.contains("<=")
                        || line.contains("≤")
                        || line.contains("数据范围")
                        || line.toLowerCase().contains("constraint"))
                .limit(8)
                .toList();
    }

    private long normalizePage(long page) {
        return Math.max(page, 1);
    }

    private long normalizePageSize(long pageSize) {
        if (pageSize <= 0) {
            return 20;
        }
        return Math.min(pageSize, 100);
    }

    private String normalizeTag(String tag) {
        return tag == null ? null : tag.trim();
    }

    private String normalizeSolutionLanguage(String language) {
        if (!StringUtils.hasText(language)) {
            return "cpp";
        }
        String normalized = language.trim().toLowerCase();
        if ("c++".equals(normalized) || "cpp17".equals(normalized) || "c++17".equals(normalized)) {
            return "cpp";
        }
        if ("py".equals(normalized) || "python3".equals(normalized)) {
            return "python";
        }
        return normalized;
    }
}
