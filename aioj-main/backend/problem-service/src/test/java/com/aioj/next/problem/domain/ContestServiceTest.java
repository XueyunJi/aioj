package com.aioj.next.problem.domain;

import com.aioj.next.common.error.DomainException;
import com.aioj.next.common.error.ErrorCode;
import com.aioj.next.common.security.Role;
import com.aioj.next.common.security.SecurityPrincipal;
import com.aioj.next.contract.contest.ContestCreateRequest;
import com.aioj.next.contract.contest.ContestMode;
import com.aioj.next.contract.contest.ContestParticipantStatus;
import com.aioj.next.contract.contest.ContestParticipantType;
import com.aioj.next.contract.contest.ContestProblemBatchUpdateRequest;
import com.aioj.next.contract.contest.ContestProblemRequest;
import com.aioj.next.contract.contest.ContestProblemScoringMode;
import com.aioj.next.contract.contest.ContestRunKind;
import com.aioj.next.contract.contest.ContestRunStatus;
import com.aioj.next.contract.contest.ContestStatus;
import com.aioj.next.contract.contest.ContestVisibility;
import com.aioj.next.contract.contest.ContestUpdateRequest;
import com.aioj.next.contract.learning.LearningGroupMemberRole;
import com.aioj.next.contract.learning.LearningGroupStatus;
import com.aioj.next.contract.learning.LearningGroupType;
import com.aioj.next.contract.problem.Difficulty;
import com.aioj.next.problem.persistence.entity.ContestParticipantEntity;
import com.aioj.next.problem.persistence.entity.ContestParticipantSnapshotEntity;
import com.aioj.next.problem.persistence.entity.ContestEntity;
import com.aioj.next.problem.persistence.entity.ContestProblemEntity;
import com.aioj.next.problem.persistence.entity.ContestRunEntity;
import com.aioj.next.problem.persistence.entity.LearningGroupEntity;
import com.aioj.next.problem.persistence.entity.LearningGroupMemberEntity;
import com.aioj.next.problem.persistence.entity.ProblemEntity;
import com.aioj.next.problem.persistence.entity.UserAccountEntity;
import com.aioj.next.problem.persistence.mapper.ContestParticipantMapper;
import com.aioj.next.problem.persistence.mapper.ContestParticipantSnapshotMapper;
import com.aioj.next.problem.persistence.mapper.ContestMapper;
import com.aioj.next.problem.persistence.mapper.ContestProblemMapper;
import com.aioj.next.problem.persistence.mapper.ContestProblemScoringRuleMapper;
import com.aioj.next.problem.persistence.mapper.ContestRunMapper;
import com.aioj.next.problem.persistence.mapper.LearningGroupMapper;
import com.aioj.next.problem.persistence.mapper.LearningGroupMemberMapper;
import com.aioj.next.problem.persistence.mapper.UserAccountMapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.time.Instant;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ContestServiceTest {
    @Mock
    private ContestMapper contestMapper;
    @Mock
    private ContestProblemMapper contestProblemMapper;
    @Mock
    private ContestProblemScoringRuleMapper scoringRuleMapper;
    @Mock
    private LearningGroupMapper learningGroupMapper;
    @Mock
    private LearningGroupMemberMapper learningGroupMemberMapper;
    @Mock
    private ContestParticipantMapper contestParticipantMapper;
    @Mock
    private ContestParticipantSnapshotMapper contestParticipantSnapshotMapper;
    @Mock
    private ContestRunMapper contestRunMapper;
    @Mock
    private UserAccountMapper userAccountMapper;
    @Mock
    private ProblemCatalog problemCatalog;

    private ContestService service;

    @BeforeEach
    void setUp() {
        service = new ContestService(contestMapper, contestProblemMapper, scoringRuleMapper, learningGroupMapper,
                learningGroupMemberMapper, contestParticipantMapper, contestParticipantSnapshotMapper,
                contestRunMapper, userAccountMapper, problemCatalog);
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void teacherCanCreateDraftContest() {
        authenticate(7L, Role.TEACHER);
        when(contestProblemMapper.selectCount(any())).thenReturn(0L);
        doAnswer(invocation -> {
            ContestEntity entity = invocation.getArgument(0);
            entity.setId(301L);
            return 1;
        }).when(contestMapper).insert(any(ContestEntity.class));

        var response = service.create(createRequest());

        assertEquals(301L, response.id());
        assertEquals(7L, response.ownerUserId());
        assertEquals(ContestStatus.DRAFT, response.status());
        ArgumentCaptor<ContestEntity> captor = ArgumentCaptor.forClass(ContestEntity.class);
        verify(contestMapper).insert(captor.capture());
        assertEquals("Spring Invitational", captor.getValue().getTitle());
    }

    @Test
    void createPersistsAiPolicyModeAndNotes() {
        authenticate(7L, Role.TEACHER);
        when(contestProblemMapper.selectCount(any())).thenReturn(0L);
        doAnswer(invocation -> {
            ContestEntity entity = invocation.getArgument(0);
            entity.setId(301L);
            return 1;
        }).when(contestMapper).insert(any(ContestEntity.class));

        var response = service.create(new ContestCreateRequest("Spring Invitational", "Practice", ContestMode.ACM,
                null, null, com.aioj.next.contract.contest.ContestAiPolicyMode.STRICT, "no code hints"));

        assertEquals(com.aioj.next.contract.contest.ContestAiPolicyMode.STRICT, response.aiPolicyMode());
        assertEquals("no code hints", response.aiPolicyNotes());
        ArgumentCaptor<ContestEntity> captor = ArgumentCaptor.forClass(ContestEntity.class);
        verify(contestMapper).insert(captor.capture());
        assertEquals(com.aioj.next.contract.contest.ContestAiPolicyMode.STRICT, captor.getValue().getAiPolicyMode());
    }

    @Test
    void draftUpdatePersistsAiPolicyFields() {
        authenticate(7L, Role.TEACHER);
        when(contestMapper.selectById(301L)).thenReturn(contest(301L, 7L, ContestStatus.DRAFT));
        when(contestProblemMapper.selectCount(any())).thenReturn(0L);

        var response = service.update(301L, new ContestUpdateRequest(null, null, null, null, null,
                com.aioj.next.contract.contest.ContestAiPolicyMode.DISABLED, "practice run"));

        assertEquals(com.aioj.next.contract.contest.ContestAiPolicyMode.DISABLED, response.aiPolicyMode());
        assertEquals("practice run", response.aiPolicyNotes());
    }

    @Test
    void createRejectsDuplicateActiveContestTitle() {
        authenticate(7L, Role.TEACHER);
        when(contestMapper.selectCount(any())).thenReturn(1L);

        DomainException error = assertThrows(DomainException.class, () -> service.create(createRequest()));

        assertEquals(ErrorCode.CONFLICT, error.errorCode());
        assertEquals("Contest title already exists", error.getMessage());
        verify(contestMapper, never()).insert(any(ContestEntity.class));
    }

    @Test
    void createAllowsTitleWhenOnlyDeletedDuplicateExists() {
        authenticate(7L, Role.TEACHER);
        when(contestMapper.selectCount(any())).thenReturn(0L);
        when(contestProblemMapper.selectCount(any())).thenReturn(0L);
        doAnswer(invocation -> {
            ContestEntity entity = invocation.getArgument(0);
            entity.setId(301L);
            return 1;
        }).when(contestMapper).insert(any(ContestEntity.class));

        var response = service.create(createRequest());

        assertEquals(301L, response.id());
        verify(contestMapper).insert(any(ContestEntity.class));
    }

    @Test
    void unrelatedTeacherCannotReadContest() {
        authenticate(8L, Role.TEACHER);
        when(contestMapper.selectById(301L)).thenReturn(contest(301L, 7L, ContestStatus.DRAFT));

        DomainException error = assertThrows(DomainException.class, () -> service.get(301L));

        assertEquals(ErrorCode.FORBIDDEN, error.errorCode());
    }

    @Test
    void studentOnlyListsPublishedGroupContests() {
        authenticate(9L, Role.STUDENT);
        LearningGroupMemberEntity member = new LearningGroupMemberEntity();
        member.setGroupId(101L);
        member.setUserId(9L);
        when(learningGroupMemberMapper.selectList(any())).thenReturn(List.of(member));
        Page<ContestEntity> page = new Page<>(1, 20);
        page.setRecords(List.of(contest(301L, 7L, ContestStatus.PUBLISHED)));
        page.setTotal(1);
        when(contestMapper.selectPage(any(), any())).thenReturn(page);
        when(contestProblemMapper.selectCount(any())).thenReturn(2L);

        var response = service.list(1, 20, null, null, null, null, null);

        assertEquals(1, response.total());
        assertEquals(ContestStatus.PUBLISHED, response.records().get(0).status());
    }

    @Test
    void publishRejectsDuplicateProblemLabels() {
        authenticate(7L, Role.TEACHER);
        when(contestMapper.selectById(301L)).thenReturn(contest(301L, 7L, ContestStatus.DRAFT));
        when(problemCatalog.existsActive(anyLong())).thenReturn(true);
        when(contestProblemMapper.selectList(any())).thenReturn(List.of(
                contestProblem(301L, 1001L, "A"),
                contestProblem(301L, 1002L, "A")
        ));

        DomainException error = assertThrows(DomainException.class, () -> service.publish(301L));

        assertEquals(ErrorCode.BAD_REQUEST, error.errorCode());
        assertEquals("Contest problem labels must be unique", error.getMessage());
        verify(contestMapper, never()).updateById(any(ContestEntity.class));
    }

    @Test
    void publishedContestProblemsCannotBeChanged() {
        authenticate(7L, Role.TEACHER);
        when(contestMapper.selectById(301L)).thenReturn(contest(301L, 7L, ContestStatus.PUBLISHED));

        DomainException error = assertThrows(DomainException.class,
                () -> service.replaceProblems(301L, new ContestProblemBatchUpdateRequest(List.of(
                        new ContestProblemRequest(1001L, "A", "A", 100, 0,
                                ContestProblemScoringMode.CASE_SUM_BEST_SUBMISSION)
                ))));

        assertEquals(ErrorCode.BAD_REQUEST, error.errorCode());
        assertEquals("Confirmed contest blueprint problems cannot be changed", error.getMessage());
    }

    @Test
    void confirmedContestCanUpdateDescriptionOnly() {
        authenticate(7L, Role.TEACHER);
        when(contestMapper.selectById(301L)).thenReturn(contest(301L, 7L, ContestStatus.PUBLISHED));

        var response = service.update(301L, new ContestUpdateRequest(null, "Updated rules", null, null, null, null, null));

        assertEquals("Spring Invitational", response.title());
        assertEquals(ContestMode.ACM, response.mode());
        ArgumentCaptor<ContestEntity> captor = ArgumentCaptor.forClass(ContestEntity.class);
        verify(contestMapper).updateById(captor.capture());
        assertEquals("Updated rules", captor.getValue().getDescription());
        assertEquals(Boolean.FALSE, captor.getValue().getCePenalty());
    }

    @Test
    void confirmedContestRejectsReusableRuleChanges() {
        authenticate(7L, Role.TEACHER);
        when(contestMapper.selectById(301L)).thenReturn(contest(301L, 7L, ContestStatus.PUBLISHED));

        DomainException error = assertThrows(DomainException.class,
                () -> service.update(301L, new ContestUpdateRequest("Updated Invitational", "Updated rules", ContestMode.IOI, 30, Boolean.TRUE, null, null)));

        assertEquals(ErrorCode.BAD_REQUEST, error.errorCode());
        assertEquals("Published contest only allows description updates", error.getMessage());
        verify(contestMapper, never()).updateById(any(ContestEntity.class));
    }

    @Test
    void draftUpdateRejectsDuplicateContestTitle() {
        authenticate(7L, Role.TEACHER);
        when(contestMapper.selectById(301L)).thenReturn(contest(301L, 7L, ContestStatus.DRAFT));
        when(contestMapper.selectCount(any())).thenReturn(1L);

        DomainException error = assertThrows(DomainException.class,
                () -> service.update(301L, new ContestUpdateRequest("Spring Invitational 2", null, null, null, null, null, null)));

        assertEquals(ErrorCode.CONFLICT, error.errorCode());
        assertEquals("Contest title already exists", error.getMessage());
        verify(contestMapper, never()).updateById(any(ContestEntity.class));
    }

    @Test
    void archivedConfirmedContestRestoresToPublishedReadyState() {
        authenticate(7L, Role.TEACHER);
        Instant publishedAt = Instant.parse("2026-06-10T08:00:00Z");
        ContestEntity archived = contest(301L, 7L, ContestStatus.ARCHIVED);
        archived.setPublishedAt(publishedAt);
        archived.setArchivedAt(Instant.parse("2026-06-11T08:00:00Z"));
        when(contestMapper.selectById(301L)).thenReturn(archived);
        when(contestProblemMapper.selectCount(any())).thenReturn(3L);

        var response = service.restore(301L);

        assertEquals(ContestStatus.PUBLISHED, response.status());
        assertEquals(publishedAt, response.publishedAt());
        assertNull(response.archivedAt());
        ArgumentCaptor<ContestEntity> captor = ArgumentCaptor.forClass(ContestEntity.class);
        verify(contestMapper).updateById(captor.capture());
        assertEquals(ContestStatus.PUBLISHED, captor.getValue().getStatus());
        assertEquals(publishedAt, captor.getValue().getPublishedAt());
        assertNull(captor.getValue().getArchivedAt());
    }

    @Test
    void archivedUnconfirmedContestRestoresToDraft() {
        authenticate(7L, Role.TEACHER);
        ContestEntity archived = contest(301L, 7L, ContestStatus.ARCHIVED);
        archived.setArchivedAt(Instant.parse("2026-06-11T08:00:00Z"));
        when(contestMapper.selectById(301L)).thenReturn(archived);

        var response = service.restore(301L);

        assertEquals(ContestStatus.DRAFT, response.status());
        assertNull(response.publishedAt());
        assertNull(response.archivedAt());
        ArgumentCaptor<ContestEntity> captor = ArgumentCaptor.forClass(ContestEntity.class);
        verify(contestMapper).updateById(captor.capture());
        assertEquals(ContestStatus.DRAFT, captor.getValue().getStatus());
        assertNull(captor.getValue().getPublishedAt());
        assertNull(captor.getValue().getArchivedAt());
    }

    @Test
    void restoreRejectsDuplicateActiveContestTitle() {
        authenticate(7L, Role.TEACHER);
        ContestEntity archived = contest(301L, 7L, ContestStatus.ARCHIVED);
        archived.setArchivedAt(Instant.parse("2026-06-11T08:00:00Z"));
        when(contestMapper.selectById(301L)).thenReturn(archived);
        when(contestMapper.selectCount(any())).thenReturn(1L);

        DomainException error = assertThrows(DomainException.class, () -> service.restore(301L));

        assertEquals(ErrorCode.CONFLICT, error.errorCode());
        assertEquals("Contest title already exists", error.getMessage());
        verify(contestMapper, never()).updateById(any(ContestEntity.class));
    }

    @Test
    void restoreRejectsNonArchivedContest() {
        authenticate(7L, Role.TEACHER);
        when(contestMapper.selectById(301L)).thenReturn(contest(301L, 7L, ContestStatus.PUBLISHED));

        DomainException error = assertThrows(DomainException.class, () -> service.restore(301L));

        assertEquals(ErrorCode.BAD_REQUEST, error.errorCode());
        assertEquals("Only archived contests can be restored", error.getMessage());
        verify(contestMapper, never()).updateById(any(ContestEntity.class));
    }

    @Test
    void replaceProblemsRejectsDeletedProblem() {
        authenticate(7L, Role.TEACHER);
        when(contestMapper.selectById(301L)).thenReturn(contest(301L, 7L, ContestStatus.DRAFT));
        when(problemCatalog.findActive(1001L)).thenReturn(java.util.Optional.empty());

        DomainException error = assertThrows(DomainException.class,
                () -> service.replaceProblems(301L, new ContestProblemBatchUpdateRequest(List.of(
                        new ContestProblemRequest(1001L, "A", null, 100, 0,
                                ContestProblemScoringMode.CASE_SUM_BEST_SUBMISSION)
                ))));

        assertEquals(ErrorCode.NOT_FOUND, error.errorCode());
        assertEquals("Problem not found", error.getMessage());
    }

    @Test
    void importScopeGroupParticipantsCreatesSnapshotsForStudents() {
        authenticate(7L, Role.TEACHER);
        when(contestMapper.selectById(301L)).thenReturn(contest(301L, 7L, ContestStatus.DRAFT));
        when(learningGroupMapper.selectOne(any())).thenReturn(activeGroup(101L));
        when(learningGroupMemberMapper.selectList(any())).thenReturn(List.of(groupMember(101L, 9L, LearningGroupMemberRole.STUDENT)));
        when(userAccountMapper.selectBatchIds(any())).thenReturn(List.of(user(9L, "student9", "Student 9")));
        when(contestParticipantMapper.selectOne(any())).thenReturn(null);
        doAnswer(invocation -> {
            ContestParticipantEntity participant = invocation.getArgument(0);
            participant.setId(501L);
            return 1;
        }).when(contestParticipantMapper).insert(any(ContestParticipantEntity.class));
        ContestParticipantEntity listed = participant(301L, 9L);
        listed.setAccountSnapshot("student9");
        listed.setDisplayNameSnapshot("Student 9");
        listed.setStatus(ContestParticipantStatus.ACTIVE);
        when(contestParticipantMapper.selectList(any())).thenReturn(List.of(listed));

        var participants = service.importScopeGroupParticipants(301L);

        assertEquals(1, participants.size());
        assertEquals(ContestParticipantStatus.ACTIVE, participants.get(0).status());
        ArgumentCaptor<ContestParticipantEntity> participantCaptor = ArgumentCaptor.forClass(ContestParticipantEntity.class);
        verify(contestParticipantMapper).insert(participantCaptor.capture());
        assertEquals(ContestParticipantType.INDIVIDUAL, participantCaptor.getValue().getParticipantType());
        ArgumentCaptor<ContestParticipantSnapshotEntity> snapshotCaptor = ArgumentCaptor.forClass(ContestParticipantSnapshotEntity.class);
        verify(contestParticipantSnapshotMapper).insert(snapshotCaptor.capture());
        assertEquals("GROUP_IMPORT", snapshotCaptor.getValue().getSnapshotReason());
    }

    @Test
    void resolveSubmissionContextRejectsNonParticipant() {
        authenticate(9L, Role.STUDENT);
        ContestEntity contest = contest(301L, 7L, ContestStatus.PUBLISHED);
        when(contestMapper.selectById(301L)).thenReturn(contest);
        when(contestProblemMapper.selectOne(any())).thenReturn(contestProblem(301L, 1001L, "A"));
        when(contestParticipantMapper.selectOne(any())).thenReturn(null);

        DomainException error = assertThrows(DomainException.class,
                () -> service.resolveSubmissionContext(301L, 401L, 1001L, 9L, contest.getStartAt().plusSeconds(60)));

        assertEquals(ErrorCode.FORBIDDEN, error.errorCode());
        assertEquals("User is not an active participant in this contest", error.getMessage());
    }

    @Test
    void resolveSubmissionContextReturnsContestParticipantAndElapsedTime() {
        authenticate(9L, Role.STUDENT);
        ContestEntity contest = contest(301L, 7L, ContestStatus.PUBLISHED);
        ContestProblemEntity contestProblem = contestProblem(301L, 1001L, "A");
        ContestParticipantEntity participant = participant(301L, 9L);
        when(contestMapper.selectById(301L)).thenReturn(contest);
        when(contestProblemMapper.selectOne(any())).thenReturn(contestProblem);
        when(contestParticipantMapper.selectOne(any())).thenReturn(participant);

        var context = service.resolveSubmissionContext(301L, 401L, 1001L, 9L, contest.getStartAt().plusSeconds(90));

        assertEquals(301L, context.contest().getId());
        assertEquals(401L, context.contestProblem().getId());
        assertEquals(601L, context.participant().getId());
        assertEquals(90_000L, context.submittedAtContestMillis());
    }

    @Test
    void resolveSubmissionContextRejectsNoRegistrationWindowRunBeforeStart() {
        authenticate(9L, Role.STUDENT);
        Instant start = Instant.parse("2026-06-10T09:00:00Z");
        ContestEntity contest = contest(301L, 7L, ContestStatus.PUBLISHED);
        ContestRunEntity run = contestRun(501L, start, start.plusSeconds(7200), ContestRunKind.FORMAL);
        when(contestMapper.selectById(301L)).thenReturn(contest);
        when(contestRunMapper.selectById(501L)).thenReturn(run);

        DomainException error = assertThrows(DomainException.class,
                () -> service.resolveSubmissionContext(301L, 501L, 401L, 1001L, 9L, start.minusSeconds(600)));

        assertEquals(ErrorCode.BAD_REQUEST, error.errorCode());
        assertEquals("Contest has not started", error.getMessage());
        verify(contestParticipantMapper, never()).insert(any(ContestParticipantEntity.class));
    }

    @Test
    void resolveSubmissionContextRejectsNoRegistrationWindowRunAfterEnd() {
        authenticate(9L, Role.STUDENT);
        Instant start = Instant.parse("2026-06-10T09:00:00Z");
        ContestEntity contest = contest(301L, 7L, ContestStatus.PUBLISHED);
        ContestRunEntity run = contestRun(501L, start, start.plusSeconds(7200), ContestRunKind.FORMAL);
        when(contestMapper.selectById(301L)).thenReturn(contest);
        when(contestRunMapper.selectById(501L)).thenReturn(run);

        DomainException error = assertThrows(DomainException.class,
                () -> service.resolveSubmissionContext(301L, 501L, 401L, 1001L, 9L, start.plusSeconds(7200)));

        assertEquals(ErrorCode.BAD_REQUEST, error.errorCode());
        assertEquals("Contest has ended", error.getMessage());
        verify(contestParticipantMapper, never()).insert(any(ContestParticipantEntity.class));
    }

    private ContestCreateRequest createRequest() {
        return new ContestCreateRequest(" Spring Invitational ", " Practice contest ", ContestMode.ACM,
                null, null, null, null);
    }

    private ContestEntity contest(Long id, Long ownerUserId, ContestStatus status) {
        Instant start = Instant.parse("2026-06-10T09:00:00Z");
        ContestEntity contest = new ContestEntity();
        contest.setId(id);
        contest.setOwnerUserId(ownerUserId);
        contest.setScopeGroupId(101L);
        contest.setTitle("Spring Invitational");
        contest.setMode(ContestMode.ACM);
        contest.setStatus(status);
        contest.setVisibility(ContestVisibility.GROUP);
        contest.setStartAt(start);
        contest.setEndAt(start.plusSeconds(18_000));
        contest.setPenaltyMinutes(20);
        contest.setCePenalty(false);
        contest.setCreatedAt(start.minusSeconds(3600));
        contest.setUpdatedAt(start.minusSeconds(3600));
        return contest;
    }

    private ContestProblemEntity contestProblem(Long contestId, Long problemId, String label) {
        ContestProblemEntity problem = new ContestProblemEntity();
        problem.setId(401L);
        problem.setContestId(contestId);
        problem.setProblemId(problemId);
        problem.setLabel(label);
        problem.setDisplayTitle(label);
        problem.setScore(100);
        problem.setSortOrder(0);
        return problem;
    }

    private ContestRunEntity contestRun(Long id, Instant startAt, Instant endAt, ContestRunKind kind) {
        ContestRunEntity run = new ContestRunEntity();
        run.setId(id);
        run.setContestId(301L);
        run.setRunKind(kind);
        run.setStatus(ContestRunStatus.SCHEDULED);
        run.setTitle("Development Run");
        run.setStartAt(startAt);
        run.setEndAt(endAt);
        return run;
    }

    private ContestParticipantEntity participant(Long contestId, Long userId) {
        ContestParticipantEntity participant = new ContestParticipantEntity();
        participant.setId(601L);
        participant.setContestId(contestId);
        participant.setUserId(userId);
        participant.setStatus(ContestParticipantStatus.ACTIVE);
        participant.setParticipantType(ContestParticipantType.INDIVIDUAL);
        return participant;
    }

    private LearningGroupMemberEntity groupMember(Long groupId, Long userId, LearningGroupMemberRole role) {
        LearningGroupMemberEntity member = new LearningGroupMemberEntity();
        member.setGroupId(groupId);
        member.setUserId(userId);
        member.setRole(role);
        member.setCreatedAt(Instant.parse("2026-06-01T08:00:00Z"));
        return member;
    }

    private UserAccountEntity user(Long id, String account, String displayName) {
        UserAccountEntity user = new UserAccountEntity();
        user.setId(id);
        user.setAccount(account);
        user.setDisplayName(displayName);
        user.setEmail(account + "@example.com");
        user.setEnabled(true);
        return user;
    }

    private LearningGroupEntity activeGroup(Long id) {
        LearningGroupEntity group = new LearningGroupEntity();
        group.setId(id);
        group.setName("Class " + id);
        group.setType(LearningGroupType.CLASS);
        group.setStatus(LearningGroupStatus.ACTIVE);
        return group;
    }

    @SuppressWarnings("unused")
    private ProblemEntity problem(Long id, String title) {
        ProblemEntity problem = new ProblemEntity();
        problem.setId(id);
        problem.setTitle(title);
        problem.setDifficulty(Difficulty.EASY);
        problem.setDeleted(false);
        return problem;
    }

    private void authenticate(Long userId, Role... roles) {
        Set<Role> roleSet = Set.of(roles);
        var authorities = roleSet.stream()
                .map(role -> new SimpleGrantedAuthority("ROLE_" + role.name()))
                .toList();
        SecurityPrincipal principal = new SecurityPrincipal(userId, "user" + userId, roleSet);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(principal, "n/a", authorities));
    }
}
