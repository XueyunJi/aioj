package com.aioj.next.problem.domain;

import com.aioj.next.common.error.DomainException;
import com.aioj.next.common.error.ErrorCode;
import com.aioj.next.common.security.Role;
import com.aioj.next.common.security.SecurityPrincipal;
import com.aioj.next.contract.ai.PlagiarismAnalysisResponse;
import com.aioj.next.contract.contest.ContestMode;
import com.aioj.next.contract.contest.ContestRunStatus;
import com.aioj.next.contract.contest.ContestStatus;
import com.aioj.next.contract.contest.ContestVisibility;
import com.aioj.next.contract.contest.PlagiarismAiStatus;
import com.aioj.next.contract.contest.PlagiarismJobCreateRequest;
import com.aioj.next.contract.contest.PlagiarismJobStatus;
import com.aioj.next.contract.contest.PlagiarismRiskLevel;
import com.aioj.next.contract.submission.SubmissionStatus;
import com.aioj.next.problem.config.PlagiarismProperties;
import com.aioj.next.problem.domain.plagiarism.PlagiarismAiAnalysisClient;
import com.aioj.next.problem.domain.plagiarism.PlagiarismDetector;
import com.aioj.next.problem.persistence.entity.ContestEntity;
import com.aioj.next.problem.persistence.entity.ContestParticipantEntity;
import com.aioj.next.problem.persistence.entity.ContestRunEntity;
import com.aioj.next.problem.persistence.entity.PlagiarismAiAnalysisEntity;
import com.aioj.next.problem.persistence.entity.PlagiarismFragmentEntity;
import com.aioj.next.problem.persistence.entity.PlagiarismJobEntity;
import com.aioj.next.problem.persistence.entity.PlagiarismJobSubmissionEntity;
import com.aioj.next.problem.persistence.entity.PlagiarismPairEntity;
import com.aioj.next.problem.persistence.entity.SubmissionEntity;
import com.aioj.next.problem.persistence.mapper.ContestMapper;
import com.aioj.next.problem.persistence.mapper.ContestParticipantMapper;
import com.aioj.next.problem.persistence.mapper.ContestProblemMapper;
import com.aioj.next.problem.persistence.mapper.ContestRunMapper;
import com.aioj.next.problem.persistence.mapper.PlagiarismAiAnalysisMapper;
import com.aioj.next.problem.persistence.mapper.PlagiarismFragmentMapper;
import com.aioj.next.problem.persistence.mapper.PlagiarismJobMapper;
import com.aioj.next.problem.persistence.mapper.PlagiarismJobSubmissionMapper;
import com.aioj.next.problem.persistence.mapper.PlagiarismPairMapper;
import com.aioj.next.problem.persistence.mapper.SubmissionMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Executor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PlagiarismServiceTest {
    @Mock
    private ContestMapper contestMapper;
    @Mock
    private ContestProblemMapper contestProblemMapper;
    @Mock
    private ContestParticipantMapper contestParticipantMapper;
    @Mock
    private ContestRunMapper contestRunMapper;
    @Mock
    private SubmissionMapper submissionMapper;
    @Mock
    private PlagiarismJobMapper jobMapper;
    @Mock
    private PlagiarismJobSubmissionMapper jobSubmissionMapper;
    @Mock
    private PlagiarismPairMapper pairMapper;
    @Mock
    private PlagiarismFragmentMapper fragmentMapper;
    @Mock
    private PlagiarismAiAnalysisMapper aiAnalysisMapper;
    @Mock
    private PlagiarismAiAnalysisClient aiAnalysisClient;

    private final List<PlagiarismJobSubmissionEntity> jobSubmissions = new ArrayList<>();
    private final List<PlagiarismPairEntity> pairs = new ArrayList<>();
    private final List<PlagiarismAiAnalysisEntity> analyses = new ArrayList<>();
    private PlagiarismJobEntity job;
    private PlagiarismDetector detector;
    private PlagiarismService service;

    @BeforeEach
    void setUp() {
        PlagiarismProperties properties = new PlagiarismProperties();
        properties.setMaxFragmentExcerptChars(500);
        Executor directExecutor = Runnable::run;
        detector = (group, options) -> {
            if (group.submissions().size() < 2) {
                return List.of();
            }
            PlagiarismDetector.DetectionSubmission left = group.submissions().get(0);
            PlagiarismDetector.DetectionSubmission right = group.submissions().get(1);
            return List.of(new PlagiarismDetector.DetectedPair(left.submissionId(), right.submissionId(),
                    0.78, 0.82, 0.76, 80,
                    List.of(new PlagiarismDetector.DetectedFragment(1, 10, 12, 20, "left excerpt", "right excerpt"))));
        };
        service = new PlagiarismService(contestMapper, contestProblemMapper, contestParticipantMapper, contestRunMapper,
                submissionMapper, jobMapper, jobSubmissionMapper, pairMapper, fragmentMapper, aiAnalysisMapper,
                detector, aiAnalysisClient, properties, new ObjectMapper().findAndRegisterModules(), directExecutor);

        lenient().when(jobMapper.selectById(501L)).thenAnswer(invocation -> job);
        lenient().when(jobMapper.update(any(), any())).thenReturn(1);
        lenient().when(pairMapper.selectList(any())).thenReturn(pairs);
        lenient().when(submissionMapper.selectCount(any())).thenReturn(0L);
        lenient().when(contestParticipantMapper.selectList(any())).thenReturn(List.of(
                participant(101L, 11L, "alice"),
                participant(102L, 12L, "bob")
        ));
        lenient().doAnswer(invocation -> {
            PlagiarismJobSubmissionEntity entity = invocation.getArgument(0);
            entity.setId(1000L + jobSubmissions.size() + 1);
            jobSubmissions.add(entity);
            return 1;
        }).when(jobSubmissionMapper).insert(any(PlagiarismJobSubmissionEntity.class));
        lenient().doAnswer(invocation -> {
            PlagiarismPairEntity entity = invocation.getArgument(0);
            entity.setId(2000L + pairs.size() + 1);
            pairs.add(entity);
            return 1;
        }).when(pairMapper).insert(any(PlagiarismPairEntity.class));
        lenient().doAnswer(invocation -> 1).when(fragmentMapper).insert(any(PlagiarismFragmentEntity.class));
        lenient().doAnswer(invocation -> {
            PlagiarismAiAnalysisEntity entity = invocation.getArgument(0);
            entity.setId(3000L + analyses.size() + 1);
            analyses.add(entity);
            return 1;
        }).when(aiAnalysisMapper).insert(any(PlagiarismAiAnalysisEntity.class));
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void unrelatedTeacherCannotCreatePlagiarismJob() {
        authenticate(8L, Role.TEACHER);
        when(contestMapper.selectById(301L)).thenReturn(contest(301L, 7L));

        DomainException error = assertThrows(DomainException.class,
                () -> service.createJob(301L, new PlagiarismJobCreateRequest(List.of(), List.of(), 0.55, false)));

        assertThat(error.errorCode()).isEqualTo(ErrorCode.FORBIDDEN);
    }

    @Test
    void archivedRunCannotCreatePlagiarismJob() {
        authenticate(7L, Role.TEACHER);
        when(contestMapper.selectById(301L)).thenReturn(contest(301L, 7L));
        when(contestRunMapper.selectById(401L)).thenReturn(run(401L, ContestRunStatus.ARCHIVED, false));

        DomainException error = assertThrows(DomainException.class,
                () -> service.createJob(301L, 401L, new PlagiarismJobCreateRequest(List.of(), List.of(), 0.55, false)));

        assertThat(error.errorCode()).isEqualTo(ErrorCode.BAD_REQUEST);
    }

    @Test
    void deletedRunCannotCreatePlagiarismJob() {
        authenticate(7L, Role.TEACHER);
        when(contestMapper.selectById(301L)).thenReturn(contest(301L, 7L));
        when(contestRunMapper.selectById(401L)).thenReturn(run(401L, ContestRunStatus.ENDED, true));

        DomainException error = assertThrows(DomainException.class,
                () -> service.createJob(301L, 401L, new PlagiarismJobCreateRequest(List.of(), List.of(), 0.55, false)));

        assertThat(error.errorCode()).isEqualTo(ErrorCode.NOT_FOUND);
    }

    @Test
    void scheduledRunWhoseEndTimePassedCanCreatePlagiarismJob() {
        authenticate(7L, Role.TEACHER);
        when(contestMapper.selectById(301L)).thenReturn(contest(301L, 7L));
        when(contestRunMapper.selectById(401L)).thenReturn(run(401L, ContestRunStatus.SCHEDULED, false));
        doAnswer(invocation -> {
            PlagiarismJobEntity entity = invocation.getArgument(0);
            entity.setId(501L);
            job = entity;
            return 1;
        }).when(jobMapper).insert(any(PlagiarismJobEntity.class));
        when(submissionMapper.selectList(any())).thenReturn(List.of());

        var response = service.createJob(301L, 401L,
                new PlagiarismJobCreateRequest(List.of(), List.of(), 0.55, false));

        assertThat(response.contestRunId()).isEqualTo(401L);
        assertThat(job.getStatus()).isEqualTo(PlagiarismJobStatus.COMPLETED);
    }

    @Test
    void restoredRunWithStaleArchivedAtCanCreatePlagiarismJob() {
        authenticate(7L, Role.TEACHER);
        ContestRunEntity run = run(401L, ContestRunStatus.SCHEDULED, false);
        run.setArchivedAt(Instant.parse("2026-06-02T00:00:00Z"));
        run.setStatusBeforeArchive(ContestRunStatus.SCHEDULED);
        when(contestMapper.selectById(301L)).thenReturn(contest(301L, 7L));
        when(contestRunMapper.selectById(401L)).thenReturn(run);
        doAnswer(invocation -> {
            PlagiarismJobEntity entity = invocation.getArgument(0);
            entity.setId(501L);
            job = entity;
            return 1;
        }).when(jobMapper).insert(any(PlagiarismJobEntity.class));
        when(submissionMapper.selectList(any())).thenReturn(List.of());

        var response = service.createJob(301L, 401L,
                new PlagiarismJobCreateRequest(List.of(), List.of(), 0.55, false));

        assertThat(response.contestRunId()).isEqualTo(401L);
        assertThat(job.getStatus()).isEqualTo(PlagiarismJobStatus.COMPLETED);
    }

    @Test
    void runWithUnfinishedSubmissionsCannotCreatePlagiarismJob() {
        authenticate(7L, Role.TEACHER);
        when(contestMapper.selectById(301L)).thenReturn(contest(301L, 7L));
        when(contestRunMapper.selectById(401L)).thenReturn(run(401L, ContestRunStatus.SCHEDULED, false));
        when(submissionMapper.selectCount(any())).thenReturn(1L);

        DomainException error = assertThrows(DomainException.class,
                () -> service.createJob(301L, 401L, new PlagiarismJobCreateRequest(List.of(), List.of(), 0.55, false)));

        assertThat(error.errorCode()).isEqualTo(ErrorCode.BAD_REQUEST);
    }

    @Test
    void runJobPromotesRepeatedHighRiskPairsAndCompletes() {
        job = plagiarismJob(false);
        when(submissionMapper.selectList(any())).thenReturn(List.of(
                submission(1L, 9001L, 101L, 11L, "cpp"),
                submission(2L, 9001L, 102L, 12L, "cpp"),
                submission(3L, 9002L, 101L, 11L, "cpp"),
                submission(4L, 9002L, 102L, 12L, "cpp")
        ));

        service.runJob(501L, new PlagiarismJobCreateRequest(List.of(), List.of("cpp"), 0.55, false));

        assertThat(job.getStatus()).as(job.getErrorMessage()).isEqualTo(PlagiarismJobStatus.COMPLETED);
        assertThat(job.getTotalSubmissions()).isEqualTo(4);
        assertThat(job.getTotalPairs()).isEqualTo(2);
        assertThat(job.getHighRiskPairs()).isEqualTo(2);
        assertThat(pairs).hasSize(2);
        assertThat(pairs).allSatisfy(pair -> assertThat(pair.getRiskLevel()).isEqualTo(PlagiarismRiskLevel.CRITICAL));
    }

    @Test
    void aiFailureDoesNotFailCompletedJob() {
        job = plagiarismJob(true);
        when(submissionMapper.selectList(any())).thenReturn(List.of(
                submission(1L, 9001L, 101L, 11L, "cpp"),
                submission(2L, 9001L, 102L, 12L, "cpp")
        ));
        when(jobSubmissionMapper.selectBatchIds(any())).thenReturn(jobSubmissions);
        when(fragmentMapper.selectList(any())).thenReturn(List.of());
        when(aiAnalysisClient.analyze(any())).thenReturn(new PlagiarismAnalysisResponse(
                null, "mock", "mock-model", 10, 4, false, "provider unavailable"));

        service.runJob(501L, new PlagiarismJobCreateRequest(List.of(), List.of("cpp"), 0.55, true));

        assertThat(job.getStatus()).as(job.getErrorMessage()).isEqualTo(PlagiarismJobStatus.COMPLETED);
        assertThat(pairs).singleElement().satisfies(pair -> {
            assertThat(pair.getRiskLevel()).isEqualTo(PlagiarismRiskLevel.HIGH);
            assertThat(pair.getAiStatus()).isEqualTo(PlagiarismAiStatus.FAILED);
        });
        assertThat(analyses).singleElement().satisfies(analysis -> {
            assertThat(analysis.getStatus()).isEqualTo(PlagiarismAiStatus.FAILED);
            assertThat(analysis.getErrorMessage()).contains("provider unavailable");
        });
    }

    private PlagiarismJobEntity plagiarismJob(boolean includeAi) {
        PlagiarismJobEntity entity = new PlagiarismJobEntity();
        entity.setId(501L);
        entity.setContestId(301L);
        entity.setStatus(PlagiarismJobStatus.QUEUED);
        entity.setMinimumSimilarity(0.55);
        entity.setIncludeAiAnalysis(includeAi);
        entity.setCreatedBy(7L);
        entity.setCreatedAt(Instant.now());
        entity.setUpdatedAt(Instant.now());
        return entity;
    }

    private ContestEntity contest(Long id, Long ownerUserId) {
        ContestEntity entity = new ContestEntity();
        entity.setId(id);
        entity.setOwnerUserId(ownerUserId);
        entity.setScopeGroupId(201L);
        entity.setTitle("Spring Contest");
        entity.setMode(ContestMode.ACM);
        entity.setStatus(ContestStatus.PUBLISHED);
        entity.setVisibility(ContestVisibility.GROUP);
        entity.setStartAt(Instant.parse("2026-06-01T00:00:00Z"));
        entity.setEndAt(Instant.parse("2026-06-01T05:00:00Z"));
        return entity;
    }

    private ContestRunEntity run(Long id, ContestRunStatus status, boolean deleted) {
        ContestRunEntity entity = new ContestRunEntity();
        entity.setId(id);
        entity.setContestId(301L);
        entity.setTitle("Final Run");
        entity.setStatus(status);
        entity.setStartAt(Instant.parse("2026-06-01T00:00:00Z"));
        entity.setEndAt(Instant.parse("2026-06-01T05:00:00Z"));
        entity.setCreatedAt(Instant.parse("2026-05-31T00:00:00Z"));
        entity.setUpdatedAt(Instant.parse("2026-05-31T00:00:00Z"));
        if (status == ContestRunStatus.ARCHIVED) {
            entity.setArchivedAt(Instant.parse("2026-06-02T00:00:00Z"));
        }
        if (deleted) {
            entity.setDeletedAt(Instant.parse("2026-06-03T00:00:00Z"));
        }
        return entity;
    }

    private ContestParticipantEntity participant(Long participantId, Long userId, String account) {
        ContestParticipantEntity entity = new ContestParticipantEntity();
        entity.setId(participantId);
        entity.setContestId(301L);
        entity.setUserId(userId);
        entity.setAccountSnapshot(account);
        entity.setDisplayNameSnapshot(account);
        return entity;
    }

    private SubmissionEntity submission(Long id, Long contestProblemId, Long participantId, Long userId, String language) {
        SubmissionEntity entity = new SubmissionEntity();
        entity.setId(id);
        entity.setContestId(301L);
        entity.setContestProblemId(contestProblemId);
        entity.setContestParticipantId(participantId);
        entity.setProblemId(contestProblemId + 1000);
        entity.setUserId(userId);
        entity.setLanguage(language);
        entity.setCode("int main(){return 0;}");
        entity.setStatus(SubmissionStatus.ACCEPTED);
        entity.setCreatedAt(Instant.now());
        return entity;
    }

    private void authenticate(Long userId, Role role) {
        SecurityPrincipal principal = new SecurityPrincipal(userId, "user-" + userId, Set.of(role));
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                principal,
                null,
                List.of(new SimpleGrantedAuthority("ROLE_" + role.name()))
        ));
    }
}
