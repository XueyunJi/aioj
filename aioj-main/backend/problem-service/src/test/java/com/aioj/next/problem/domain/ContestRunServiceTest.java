package com.aioj.next.problem.domain;

import com.aioj.next.common.error.DomainException;
import com.aioj.next.common.error.ErrorCode;
import com.aioj.next.common.security.Role;
import com.aioj.next.common.security.SecurityPrincipal;
import com.aioj.next.contract.contest.ContestMode;
import com.aioj.next.contract.contest.ContestInvitationBatchItemStatus;
import com.aioj.next.contract.contest.ContestInvitationBatchRequest;
import com.aioj.next.contract.contest.ContestParticipantAddRequest;
import com.aioj.next.contract.contest.ContestParticipantStatus;
import com.aioj.next.contract.contest.ContestParticipantType;
import com.aioj.next.contract.contest.ContestRegistrationAccess;
import com.aioj.next.contract.contest.ContestRegistrationPolicy;
import com.aioj.next.contract.contest.ContestRegistrationStatus;
import com.aioj.next.contract.contest.ContestRunKind;
import com.aioj.next.contract.contest.ContestRunListPurpose;
import com.aioj.next.contract.contest.ContestRunStatus;
import com.aioj.next.contract.contest.ContestStatus;
import com.aioj.next.contract.contest.ContestVisibility;
import com.aioj.next.problem.persistence.entity.ContestEntity;
import com.aioj.next.problem.persistence.entity.ContestParticipantEntity;
import com.aioj.next.problem.persistence.entity.ContestParticipantSnapshotEntity;
import com.aioj.next.problem.persistence.entity.ContestProblemEntity;
import com.aioj.next.problem.persistence.entity.ContestRegistrationEntity;
import com.aioj.next.problem.persistence.entity.ContestRunEntity;
import com.aioj.next.problem.persistence.entity.ContestRunProblemSnapshotEntity;
import com.aioj.next.problem.persistence.entity.ProblemEntity;
import com.aioj.next.problem.persistence.entity.UserAccountEntity;
import com.aioj.next.problem.persistence.mapper.ContestMapper;
import com.aioj.next.problem.persistence.mapper.ContestParticipantMapper;
import com.aioj.next.problem.persistence.mapper.ContestParticipantSnapshotMapper;
import com.aioj.next.problem.persistence.mapper.ContestProblemMapper;
import com.aioj.next.problem.persistence.mapper.ContestProblemScoringRuleMapper;
import com.aioj.next.problem.persistence.mapper.ContestRegistrationMapper;
import com.aioj.next.problem.persistence.mapper.ContestRunAllowedGroupMapper;
import com.aioj.next.problem.persistence.mapper.ContestRunMapper;
import com.aioj.next.problem.persistence.mapper.ContestRunProblemSnapshotMapper;
import com.aioj.next.problem.persistence.mapper.LearningGroupMapper;
import com.aioj.next.problem.persistence.mapper.LearningGroupMemberMapper;
import com.aioj.next.problem.persistence.mapper.ProblemSubtaskMapper;
import com.aioj.next.problem.persistence.mapper.TestcasePackageCaseMapper;
import com.aioj.next.problem.persistence.mapper.TestcasePackageMapper;
import com.aioj.next.problem.persistence.mapper.UserAccountMapper;
import com.aioj.next.problem.domain.notification.ContestInvitationNotificationRequestedEvent;
import com.aioj.next.problem.domain.notification.UserNotificationService;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.time.Instant;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ContestRunServiceTest {
    @Mock
    private ContestMapper contestMapper;
    @Mock
    private ContestRunMapper contestRunMapper;
    @Mock
    private ContestRegistrationMapper registrationMapper;
    @Mock
    private ContestRunAllowedGroupMapper allowedGroupMapper;
    @Mock
    private ContestProblemMapper contestProblemMapper;
    @Mock
    private ContestProblemScoringRuleMapper scoringRuleMapper;
    @Mock
    private ContestRunProblemSnapshotMapper problemSnapshotMapper;
    @Mock
    private ContestParticipantMapper participantMapper;
    @Mock
    private ContestParticipantSnapshotMapper participantSnapshotMapper;
    @Mock
    private LearningGroupMapper learningGroupMapper;
    @Mock
    private LearningGroupMemberMapper learningGroupMemberMapper;
    @Mock
    private TestcasePackageMapper testcasePackageMapper;
    @Mock
    private TestcasePackageCaseMapper testcasePackageCaseMapper;
    @Mock
    private ProblemSubtaskMapper problemSubtaskMapper;
    @Mock
    private UserAccountMapper userAccountMapper;
    @Mock
    private ProblemCatalog problemCatalog;
    @Mock
    private ContestProblemVisibilityService visibilityService;
    @Mock
    private UserNotificationService notificationService;
    @Mock
    private ApplicationEventPublisher eventPublisher;

    private ContestRunService service;

    @BeforeEach
    void setUp() {
        org.mockito.Mockito.lenient().when(visibilityService.hiddenProblemIdsForRun(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any()))
                .thenReturn(java.util.Set.of());
        org.mockito.Mockito.lenient().when(visibilityService.problemVisibilityMap(org.mockito.ArgumentMatchers.any()))
                .thenReturn(java.util.Map.of());
        service = new ContestRunService(contestMapper, contestRunMapper, registrationMapper, allowedGroupMapper,
                contestProblemMapper, scoringRuleMapper, problemSnapshotMapper, participantMapper, participantSnapshotMapper,
                learningGroupMapper, learningGroupMemberMapper, testcasePackageMapper, testcasePackageCaseMapper,
                problemSubtaskMapper, userAccountMapper, problemCatalog, visibilityService, notificationService, eventPublisher,
                new ObjectMapper());
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void endedRunKeepsScoreboardVisibleButDisablesSubmit() {
        authenticate(9L, Role.STUDENT);
        Instant now = Instant.now();
        ContestEntity contest = contest(301L);
        ContestRunEntity run = run(401L, now.minusSeconds(7200), now.minusSeconds(3600), ContestRunStatus.SCHEDULED);
        when(contestMapper.selectById(301L)).thenReturn(contest);
        when(contestRunMapper.selectById(401L)).thenReturn(run);
        when(participantMapper.selectOne(any())).thenReturn(participant(401L, 9L));
        when(allowedGroupMapper.selectList(any())).thenReturn(List.of());
        when(contestProblemMapper.selectCount(any())).thenReturn(2L);

        var response = service.openRun(301L, 401L);

        assertEquals(ContestRunStatus.ENDED, response.run().status());
        assertFalse(response.canSubmit());
        assertTrue(response.canViewProblems());
        assertTrue(response.canViewScoreboard());
    }

    @Test
    void managedRunResponseDerivesEndedStatusFromTime() {
        authenticate(7L, Role.TEACHER);
        Instant now = Instant.now();
        ContestEntity contest = contest(301L);
        ContestRunEntity run = run(401L, now.minusSeconds(7200), now.minusSeconds(3600), ContestRunStatus.SCHEDULED);
        when(contestMapper.selectById(301L)).thenReturn(contest);
        when(contestRunMapper.selectById(401L)).thenReturn(run);
        when(allowedGroupMapper.selectList(any())).thenReturn(List.of());

        var response = service.getRun(301L, 401L);

        assertEquals(ContestRunStatus.ENDED, response.status());
    }

    @Test
    void expiredDraftRunCanOnlyBeArchived() {
        authenticate(7L, Role.TEACHER);
        Instant now = Instant.now();
        ContestEntity contest = contest(301L);
        ContestRunEntity run = run(401L, now.minusSeconds(7200), now.minusSeconds(3600), ContestRunStatus.DRAFT);
        when(contestMapper.selectById(301L)).thenReturn(contest);
        when(contestRunMapper.selectById(401L)).thenReturn(run);
        when(allowedGroupMapper.selectList(any())).thenReturn(List.of());

        assertEquals(ContestRunStatus.EXPIRED, service.getRun(301L, 401L).status());
        DomainException publishError = assertThrows(DomainException.class, () -> service.publishRun(301L, 401L));
        assertEquals(ErrorCode.BAD_REQUEST, publishError.errorCode());

        var archived = service.archiveRun(301L, 401L, null);

        assertEquals(ContestRunStatus.ARCHIVED, archived.status());
        assertEquals(ContestRunStatus.DRAFT, run.getStatusBeforeArchive());
        DomainException restoreError = assertThrows(DomainException.class, () -> service.restoreRun(301L, 401L));
        assertEquals(ErrorCode.BAD_REQUEST, restoreError.errorCode());
    }

    @Test
    void aiOperationsPurposeReturnsRestoredEndedRuns() {
        authenticate(7L, Role.TEACHER);
        Instant now = Instant.now();
        ContestEntity contest = contest(301L);
        ContestRunEntity run = run(401L, now.minusSeconds(7200), now.minusSeconds(3600), ContestRunStatus.SCHEDULED);
        run.setArchivedAt(now.minusSeconds(1800));
        run.setStatusBeforeArchive(ContestRunStatus.SCHEDULED);
        Page<ContestRunEntity> page = new Page<>(1, 20);
        page.setRecords(List.of(run));
        page.setTotal(1);
        when(contestMapper.selectById(301L)).thenReturn(contest);
        when(contestRunMapper.selectPage(any(), any())).thenReturn(page);
        when(allowedGroupMapper.selectList(any())).thenReturn(List.of());

        var response = service.listRuns(301L, null, null, null, null, ContestRunListPurpose.AI_OPERATIONS, 1, 20);

        assertEquals(1, response.records().size());
        assertEquals(ContestRunStatus.ENDED, response.records().get(0).status());
    }

    @Test
    void restoreRunCleansStaleArchivedAtWhenLifecycleStatusWasAlreadyRestored() {
        authenticate(7L, Role.TEACHER);
        Instant now = Instant.now();
        ContestEntity contest = contest(301L);
        ContestRunEntity run = run(401L, now.minusSeconds(7200), now.minusSeconds(3600), ContestRunStatus.SCHEDULED);
        run.setStatusBeforeArchive(ContestRunStatus.SCHEDULED);
        run.setArchivedAt(now.minusSeconds(1800));
        when(contestMapper.selectById(301L)).thenReturn(contest);
        when(contestRunMapper.selectById(401L)).thenReturn(run);
        when(allowedGroupMapper.selectList(any())).thenReturn(List.of());

        var response = service.restoreRun(301L, 401L);

        assertEquals(ContestRunStatus.ENDED, response.status());
        assertEquals(ContestRunStatus.ENDED, run.getStatus());
        assertEquals(null, run.getArchivedAt());
        assertEquals(null, run.getStatusBeforeArchive());
    }

    @Test
    void restoringArchivedRunUsesCurrentTimeStatusInsteadOfStalePreviousStatus() {
        authenticate(7L, Role.TEACHER);
        Instant now = Instant.now();
        ContestEntity contest = contest(301L);
        ContestRunEntity run = run(401L, now.minusSeconds(7200), now.minusSeconds(3600), ContestRunStatus.ARCHIVED);
        run.setStatusBeforeArchive(ContestRunStatus.SCHEDULED);
        run.setArchivedAt(now.minusSeconds(1800));
        when(contestMapper.selectById(301L)).thenReturn(contest);
        when(contestRunMapper.selectById(401L)).thenReturn(run);
        when(allowedGroupMapper.selectList(any())).thenReturn(List.of());

        var response = service.restoreRun(301L, 401L);

        assertEquals(ContestRunStatus.ENDED, response.status());
        assertEquals(ContestRunStatus.ENDED, run.getStatus());
    }

    @Test
    void restoreRunRejectsDuplicateActiveRunTitle() {
        authenticate(7L, Role.TEACHER);
        Instant now = Instant.now();
        ContestRunEntity run = run(401L, now.minusSeconds(7200), now.minusSeconds(3600), ContestRunStatus.ARCHIVED);
        run.setStatusBeforeArchive(ContestRunStatus.SCHEDULED);
        run.setArchivedAt(now.minusSeconds(1800));
        when(contestMapper.selectById(301L)).thenReturn(contest(301L));
        when(contestRunMapper.selectById(401L)).thenReturn(run);
        when(contestRunMapper.selectCount(any())).thenReturn(1L);

        DomainException error = assertThrows(DomainException.class, () -> service.restoreRun(301L, 401L));

        assertEquals(ErrorCode.CONFLICT, error.errorCode());
        assertEquals("Contest run title already exists", error.getMessage());
        verify(contestRunMapper, never()).updateById(any(ContestRunEntity.class));
    }

    @Test
    void createRunRejectsPartialRegistrationWindow() {
        authenticate(7L, Role.TEACHER);
        Instant now = Instant.now();
        when(contestMapper.selectById(301L)).thenReturn(contest(301L));

        DomainException error = assertThrows(DomainException.class, () -> service.createRun(301L,
                new com.aioj.next.contract.contest.ContestRunCreateRequest(
                        ContestRunKind.FORMAL,
                        "Round 1",
                        now.plusSeconds(3600),
                        now.plusSeconds(7200),
                        null,
                        null,
                        null,
                        ContestRegistrationAccess.PUBLIC,
                        false,
                        List.of(),
                        now,
                        null,
                        null
                )));

        assertEquals(ErrorCode.BAD_REQUEST, error.errorCode());
    }

    @Test
    void createRunRejectsDuplicateActiveRunTitle() {
        authenticate(7L, Role.TEACHER);
        Instant now = Instant.now();
        when(contestMapper.selectById(301L)).thenReturn(contest(301L));
        when(contestRunMapper.selectCount(any())).thenReturn(1L);

        DomainException error = assertThrows(DomainException.class, () -> service.createRun(301L,
                new com.aioj.next.contract.contest.ContestRunCreateRequest(
                        ContestRunKind.FORMAL,
                        "Final Run",
                        now.plusSeconds(3600),
                        now.plusSeconds(7200),
                        null,
                        null,
                        null,
                        ContestRegistrationAccess.PUBLIC,
                        false,
                        List.of(),
                        null,
                        null,
                        null
                )));

        assertEquals(ErrorCode.CONFLICT, error.errorCode());
        assertEquals("Contest run title already exists", error.getMessage());
        verify(contestRunMapper, never()).insert(any(ContestRunEntity.class));
    }

    @Test
    void createRunAllowsTitleWhenOnlyDeletedDuplicateExists() {
        authenticate(7L, Role.TEACHER);
        Instant now = Instant.now();
        when(contestMapper.selectById(301L)).thenReturn(contest(301L));
        when(contestRunMapper.selectCount(any())).thenReturn(0L);
        when(allowedGroupMapper.selectList(any())).thenReturn(List.of());
        doAnswer(invocation -> {
            ContestRunEntity run = invocation.getArgument(0);
            run.setId(401L);
            return 1;
        }).when(contestRunMapper).insert(any(ContestRunEntity.class));

        var response = service.createRun(301L, new com.aioj.next.contract.contest.ContestRunCreateRequest(
                ContestRunKind.FORMAL,
                "Final Run",
                now.plusSeconds(3600),
                now.plusSeconds(7200),
                null,
                null,
                null,
                ContestRegistrationAccess.PUBLIC,
                false,
                List.of(),
                null,
                null,
                null
        ));

        assertEquals(401L, response.id());
        verify(contestRunMapper).insert(any(ContestRunEntity.class));
    }

    @Test
    void updateRunRejectsDuplicateActiveRunTitle() {
        authenticate(7L, Role.TEACHER);
        Instant now = Instant.now();
        when(contestMapper.selectById(301L)).thenReturn(contest(301L));
        when(contestRunMapper.selectById(401L)).thenReturn(run(401L, now.plusSeconds(3600), now.plusSeconds(7200), ContestRunStatus.DRAFT));
        when(allowedGroupMapper.selectList(any())).thenReturn(List.of());
        when(contestRunMapper.selectCount(any())).thenReturn(1L);

        DomainException error = assertThrows(DomainException.class, () -> service.updateRun(301L, 401L,
                new com.aioj.next.contract.contest.ContestRunUpdateRequest(
                        "Final Run 2",
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null
                )));

        assertEquals(ErrorCode.CONFLICT, error.errorCode());
        assertEquals("Contest run title already exists", error.getMessage());
        verify(contestRunMapper, never()).updateById(any(ContestRunEntity.class));
    }

    @Test
    void inviteCreatesInvitedRegistrationWithoutParticipant() {
        authenticate(7L, Role.TEACHER);
        Instant now = Instant.now();
        ContestEntity contest = contest(301L);
        ContestRunEntity run = run(401L, now.minusSeconds(3600), now.plusSeconds(3600), ContestRunStatus.SCHEDULED);
        run.setRegistrationAccess(ContestRegistrationAccess.INVITE_ONLY);
        run.setRegistrationPolicy(ContestRegistrationPolicy.INVITE_ONLY);
        UserAccountEntity student = user(1001L, "1001", "Student 1001");
        when(contestMapper.selectById(301L)).thenReturn(contest);
        when(contestRunMapper.selectById(401L)).thenReturn(run);
        when(userAccountMapper.selectOne(any())).thenReturn(student);
        when(userAccountMapper.selectById(1001L)).thenReturn(student);
        when(participantMapper.selectOne(any())).thenReturn(null);
        when(registrationMapper.selectOne(any())).thenReturn(null);
        doAnswer(invocation -> {
            ContestRegistrationEntity registration = invocation.getArgument(0);
            registration.setId(701L);
            return 1;
        }).when(registrationMapper).insert(any(ContestRegistrationEntity.class));

        var response = service.invite(301L, 401L, new ContestParticipantAddRequest(null, "1001"));

        assertEquals(701L, response.id());
        assertEquals(1001L, response.userId());
        assertEquals(ContestRegistrationStatus.INVITED, response.status());
        ArgumentCaptor<ContestRegistrationEntity> registrationCaptor = ArgumentCaptor.forClass(ContestRegistrationEntity.class);
        verify(registrationMapper).insert(registrationCaptor.capture());
        assertEquals(1L, registrationCaptor.getValue().getInvitationNotificationVersion());
        assertEquals(0L, registrationCaptor.getValue().getInvitationNotificationDeliveredVersion());
        verify(eventPublisher).publishEvent(any(ContestInvitationNotificationRequestedEvent.class));
        verify(notificationService, never()).createContestInvitation(any(), any(), any(), any());
        verify(participantMapper, never()).insert(any(ContestParticipantEntity.class));
        verify(participantMapper, never()).updateById(any(ContestParticipantEntity.class));
        verify(participantSnapshotMapper, never()).insert(any(ContestParticipantSnapshotEntity.class));
    }

    @Test
    void inviteRefreshesCancelledRegistrationBackToInvited() {
        authenticate(7L, Role.TEACHER);
        Instant now = Instant.now();
        ContestEntity contest = contest(301L);
        ContestRunEntity run = run(402L, now.minusSeconds(3600), now.plusSeconds(3600), ContestRunStatus.SCHEDULED);
        UserAccountEntity student = user(1001L, "1001", "Student 1001");
        ContestRegistrationEntity registration = registration(702L, 402L, 1001L, ContestRegistrationStatus.CANCELLED);
        registration.setCancelledAt(now.minusSeconds(1800));
        when(contestMapper.selectById(301L)).thenReturn(contest);
        when(contestRunMapper.selectById(402L)).thenReturn(run);
        when(userAccountMapper.selectOne(any())).thenReturn(student);
        when(userAccountMapper.selectById(1001L)).thenReturn(student);
        when(participantMapper.selectOne(any())).thenReturn(null);
        when(registrationMapper.selectOne(any())).thenReturn(registration);

        var response = service.invite(301L, 402L, new ContestParticipantAddRequest(null, "1001"));

        assertEquals(702L, response.id());
        assertEquals(402L, response.contestRunId());
        assertEquals(ContestRegistrationStatus.INVITED, response.status());
        assertEquals(null, response.cancelledAt());
        assertEquals(1L, registration.getInvitationNotificationVersion());
        assertEquals(0L, registration.getInvitationNotificationDeliveredVersion());
        verify(eventPublisher).publishEvent(any(ContestInvitationNotificationRequestedEvent.class));
        verify(notificationService, never()).createContestInvitation(any(), any(), any(), any());
        verify(registrationMapper).updateById(any(ContestRegistrationEntity.class));
        verify(participantMapper, never()).insert(any(ContestParticipantEntity.class));
    }

    @Test
    void inviteExistingApprovedRegistrationIsIdempotent() {
        authenticate(7L, Role.TEACHER);
        Instant now = Instant.now();
        ContestEntity contest = contest(301L);
        ContestRunEntity run = run(401L, now.minusSeconds(3600), now.plusSeconds(3600), ContestRunStatus.SCHEDULED);
        UserAccountEntity student = user(1001L, "1001", "Student 1001");
        ContestParticipantEntity participant = participant(401L, 1001L);
        ContestRegistrationEntity registration = registration(701L, 401L, 1001L, ContestRegistrationStatus.APPROVED);
        when(contestMapper.selectById(301L)).thenReturn(contest);
        when(contestRunMapper.selectById(401L)).thenReturn(run);
        when(userAccountMapper.selectOne(any())).thenReturn(student);
        when(userAccountMapper.selectById(1001L)).thenReturn(student);
        when(participantMapper.selectOne(any())).thenReturn(participant);
        when(registrationMapper.selectOne(any())).thenReturn(registration);

        var response = service.invite(301L, 401L, new ContestParticipantAddRequest(null, "1001"));

        assertEquals(701L, response.id());
        assertEquals(ContestRegistrationStatus.APPROVED, response.status());
        verify(registrationMapper, never()).insert(any(ContestRegistrationEntity.class));
        verify(registrationMapper, never()).updateById(any(ContestRegistrationEntity.class));
        verify(participantMapper, never()).insert(any(ContestParticipantEntity.class));
        verify(participantMapper, never()).updateById(any(ContestParticipantEntity.class));
        verify(participantSnapshotMapper, never()).insert(any(ContestParticipantSnapshotEntity.class));
        verify(eventPublisher, never()).publishEvent(any(ContestInvitationNotificationRequestedEvent.class));
    }

    @Test
    void inviteExistingInvitationIsIdempotentAndRequestsOnlySafeDeliveryRecovery() {
        authenticate(7L, Role.TEACHER);
        Instant now = Instant.now();
        ContestEntity contest = contest(301L);
        ContestRunEntity run = run(401L, now.minusSeconds(3600), now.plusSeconds(3600), ContestRunStatus.SCHEDULED);
        UserAccountEntity student = user(1001L, "1001", "Student 1001");
        ContestRegistrationEntity registration = registration(701L, 401L, 1001L, ContestRegistrationStatus.INVITED);
        registration.setInvitationNotificationVersion(1L);
        registration.setInvitationNotificationDeliveredVersion(1L);
        when(contestMapper.selectById(301L)).thenReturn(contest);
        when(contestRunMapper.selectById(401L)).thenReturn(run);
        when(userAccountMapper.selectOne(any())).thenReturn(student);
        when(userAccountMapper.selectById(1001L)).thenReturn(student);
        when(participantMapper.selectOne(any())).thenReturn(null);
        when(registrationMapper.selectOne(any())).thenReturn(registration);

        var response = service.invite(301L, 401L, new ContestParticipantAddRequest(null, "1001"));

        assertEquals(ContestRegistrationStatus.INVITED, response.status());
        verify(registrationMapper, never()).insert(any(ContestRegistrationEntity.class));
        verify(registrationMapper, never()).updateById(any(ContestRegistrationEntity.class));
        verify(eventPublisher).publishEvent(any(ContestInvitationNotificationRequestedEvent.class));
        verify(notificationService, never()).createContestInvitation(any(), any(), any(), any());
    }

    @Test
    void inviteDraftRunPersistsInvitationButDefersNotificationUntilPublish() {
        authenticate(7L, Role.TEACHER);
        Instant now = Instant.now();
        ContestEntity contest = contest(301L);
        ContestRunEntity run = run(401L, now.plusSeconds(3600), now.plusSeconds(7200), ContestRunStatus.DRAFT);
        UserAccountEntity student = user(1001L, "1001", "Student 1001");
        when(contestMapper.selectById(301L)).thenReturn(contest);
        when(contestRunMapper.selectById(401L)).thenReturn(run);
        when(userAccountMapper.selectOne(any())).thenReturn(student);
        when(userAccountMapper.selectById(1001L)).thenReturn(student);
        when(participantMapper.selectOne(any())).thenReturn(null);
        when(registrationMapper.selectOne(any())).thenReturn(null);
        doAnswer(invocation -> {
            ContestRegistrationEntity registration = invocation.getArgument(0);
            registration.setId(701L);
            return 1;
        }).when(registrationMapper).insert(any(ContestRegistrationEntity.class));

        var response = service.invite(301L, 401L, new ContestParticipantAddRequest(null, "1001"));

        assertEquals(ContestRegistrationStatus.INVITED, response.status());
        verify(registrationMapper).insert(any(ContestRegistrationEntity.class));
        verify(eventPublisher, never()).publishEvent(any(ContestInvitationNotificationRequestedEvent.class));
        verify(notificationService, never()).createContestInvitation(any(), any(), any(), any());
    }

    @Test
    void inviteBatchReturnsPartialResultsAndRequestsOneRunDelivery() {
        authenticate(7L, Role.TEACHER);
        Instant now = Instant.now();
        ContestEntity contest = contest(301L);
        ContestRunEntity run = run(401L, now.minusSeconds(3600), now.plusSeconds(3600), ContestRunStatus.SCHEDULED);
        UserAccountEntity student = user(1001L, "1001", "Student 1001");
        when(contestMapper.selectById(301L)).thenReturn(contest);
        when(contestRunMapper.selectById(401L)).thenReturn(run);
        when(userAccountMapper.selectById(1001L)).thenReturn(student);
        when(userAccountMapper.selectById(1002L)).thenReturn(null);
        when(participantMapper.selectOne(any())).thenReturn(null);
        when(registrationMapper.selectOne(any())).thenReturn(null);
        doAnswer(invocation -> {
            ContestRegistrationEntity registration = invocation.getArgument(0);
            registration.setId(701L);
            return 1;
        }).when(registrationMapper).insert(any(ContestRegistrationEntity.class));

        var response = service.inviteBatch(301L, 401L, new ContestInvitationBatchRequest(List.of(1001L, 1002L)));

        assertEquals(2, response.requested());
        assertEquals(1, response.succeeded());
        assertEquals(1, response.failed());
        assertEquals(ContestInvitationBatchItemStatus.QUEUED_FOR_NOTIFICATION, response.results().get(0).status());
        assertEquals(ContestInvitationBatchItemStatus.FAILED, response.results().get(1).status());
        verify(eventPublisher).publishEvent(any(ContestInvitationNotificationRequestedEvent.class));
    }

    @Test
    void acceptInvitationApprovesRegistrationAndCreatesParticipant() {
        authenticate(1001L, Role.STUDENT);
        Instant now = Instant.now();
        ContestEntity contest = contest(301L);
        ContestRunEntity run = run(401L, now.minusSeconds(3600), now.plusSeconds(3600), ContestRunStatus.SCHEDULED);
        UserAccountEntity student = user(1001L, "1001", "Student 1001");
        ContestRegistrationEntity registration = registration(701L, 401L, 1001L, ContestRegistrationStatus.INVITED);
        when(contestMapper.selectById(301L)).thenReturn(contest);
        when(contestRunMapper.selectById(401L)).thenReturn(run);
        when(registrationMapper.selectOne(any())).thenReturn(registration);
        when(userAccountMapper.selectById(1001L)).thenReturn(student);
        when(participantMapper.selectOne(any())).thenReturn(null);
        when(allowedGroupMapper.selectList(any())).thenReturn(List.of());
        doAnswer(invocation -> {
            ContestParticipantEntity participant = invocation.getArgument(0);
            participant.setId(501L);
            return 1;
        }).when(participantMapper).insert(any(ContestParticipantEntity.class));

        var response = service.acceptInvitation(301L, 401L);

        assertEquals(ContestRegistrationStatus.APPROVED, response.status());
        assertTrue(response.approvedAt() != null);
        verify(registrationMapper).updateById(any(ContestRegistrationEntity.class));
        verify(participantMapper).insert(any(ContestParticipantEntity.class));
        verify(notificationService).markContestInvitationRead(1001L, 701L);
        ArgumentCaptor<ContestParticipantSnapshotEntity> snapshotCaptor = ArgumentCaptor.forClass(ContestParticipantSnapshotEntity.class);
        verify(participantSnapshotMapper).insert(snapshotCaptor.capture());
        assertEquals("INVITE_ACCEPT", snapshotCaptor.getValue().getSnapshotReason());
    }

    @Test
    void acceptInvitationRejectsNonPendingRegistration() {
        authenticate(1001L, Role.STUDENT);
        Instant now = Instant.now();
        when(contestMapper.selectById(301L)).thenReturn(contest(301L));
        when(contestRunMapper.selectById(401L)).thenReturn(run(401L, now.minusSeconds(3600), now.plusSeconds(3600), ContestRunStatus.SCHEDULED));
        when(registrationMapper.selectOne(any()))
                .thenReturn(registration(701L, 401L, 1001L, ContestRegistrationStatus.APPROVED));

        DomainException error = assertThrows(DomainException.class, () -> service.acceptInvitation(301L, 401L));

        assertEquals(ErrorCode.CONFLICT, error.errorCode());
        verify(participantMapper, never()).insert(any(ContestParticipantEntity.class));
    }

    @Test
    void acceptInvitationRejectsEndedRun() {
        authenticate(1001L, Role.STUDENT);
        Instant now = Instant.now();
        when(contestMapper.selectById(301L)).thenReturn(contest(301L));
        when(contestRunMapper.selectById(401L)).thenReturn(run(401L, now.minusSeconds(7200), now.minusSeconds(3600), ContestRunStatus.SCHEDULED));
        when(registrationMapper.selectOne(any()))
                .thenReturn(registration(701L, 401L, 1001L, ContestRegistrationStatus.INVITED));

        DomainException error = assertThrows(DomainException.class, () -> service.acceptInvitation(301L, 401L));

        assertEquals(ErrorCode.BAD_REQUEST, error.errorCode());
        verify(registrationMapper, never()).updateById(any(ContestRegistrationEntity.class));
        verify(participantMapper, never()).insert(any(ContestParticipantEntity.class));
    }

    @Test
    void acceptInvitationRejectsArchivedRun() {
        authenticate(1001L, Role.STUDENT);
        Instant now = Instant.now();
        when(contestMapper.selectById(301L)).thenReturn(contest(301L));
        when(contestRunMapper.selectById(401L)).thenReturn(run(401L, now.minusSeconds(3600), now.plusSeconds(3600), ContestRunStatus.ARCHIVED));
        when(registrationMapper.selectOne(any()))
                .thenReturn(registration(701L, 401L, 1001L, ContestRegistrationStatus.INVITED));

        DomainException error = assertThrows(DomainException.class, () -> service.acceptInvitation(301L, 401L));

        assertEquals(ErrorCode.BAD_REQUEST, error.errorCode());
        verify(participantMapper, never()).insert(any(ContestParticipantEntity.class));
    }

    @Test
    void acceptInvitationRejectsDraftRunBeforeCreatingParticipant() {
        authenticate(1001L, Role.STUDENT);
        Instant now = Instant.now();
        when(contestMapper.selectById(301L)).thenReturn(contest(301L));
        when(contestRunMapper.selectById(401L)).thenReturn(run(401L, now.plusSeconds(3600), now.plusSeconds(7200), ContestRunStatus.DRAFT));
        when(registrationMapper.selectOne(any()))
                .thenReturn(registration(701L, 401L, 1001L, ContestRegistrationStatus.INVITED));

        DomainException error = assertThrows(DomainException.class, () -> service.acceptInvitation(301L, 401L));

        assertEquals(ErrorCode.BAD_REQUEST, error.errorCode());
        assertEquals("Contest run is not published", error.getMessage());
        verify(registrationMapper, never()).updateById(any(ContestRegistrationEntity.class));
        verify(participantMapper, never()).insert(any(ContestParticipantEntity.class));
        verify(notificationService, never()).markContestInvitationRead(any(), any());
    }

    @Test
    void declineInvitationMarksRegistrationAsDeclined() {
        authenticate(1001L, Role.STUDENT);
        Instant now = Instant.now();
        UserAccountEntity student = user(1001L, "1001", "Student 1001");
        ContestRegistrationEntity registration = registration(701L, 401L, 1001L, ContestRegistrationStatus.INVITED);
        when(registrationMapper.selectOne(any())).thenReturn(registration);
        when(userAccountMapper.selectById(1001L)).thenReturn(student);

        var response = service.declineInvitation(301L, 401L);

        assertEquals(ContestRegistrationStatus.DECLINED, response.status());
        assertTrue(response.cancelledAt() != null);
        verify(registrationMapper).updateById(any(ContestRegistrationEntity.class));
        verify(notificationService).markContestInvitationRead(1001L, 701L);
        verify(participantMapper, never()).insert(any(ContestParticipantEntity.class));
    }

    @Test
    void declineInvitationRejectsNonPendingRegistration() {
        authenticate(1001L, Role.STUDENT);
        when(registrationMapper.selectOne(any()))
                .thenReturn(registration(701L, 401L, 1001L, ContestRegistrationStatus.CANCELLED));

        DomainException error = assertThrows(DomainException.class, () -> service.declineInvitation(301L, 401L));

        assertEquals(ErrorCode.CONFLICT, error.errorCode());
        verify(registrationMapper, never()).updateById(any(ContestRegistrationEntity.class));
    }

    @Test
    void listMyInvitationsReturnsInvitedRegistrations() {
        authenticate(1001L, Role.STUDENT);
        UserAccountEntity student = user(1001L, "1001", "Student 1001");
        Page<ContestRegistrationEntity> page = new Page<>(1, 20);
        page.setRecords(List.of(registration(701L, 401L, 1001L, ContestRegistrationStatus.INVITED)));
        page.setTotal(1);
        when(registrationMapper.selectVisibleInvitedPage(any(), org.mockito.ArgumentMatchers.eq(1001L), any())).thenReturn(page);
        when(userAccountMapper.selectById(1001L)).thenReturn(student);

        var response = service.listMyInvitations(1, 20);

        assertEquals(1, response.total());
        assertEquals(ContestRegistrationStatus.INVITED, response.records().get(0).status());
    }

    @Test
    void publishRunCopiesAiPolicyAndProblemVisibilitySnapshots() {
        authenticate(7L, Role.TEACHER);
        Instant now = Instant.now();
        ContestEntity contest = contest(301L);
        contest.setAiPolicyMode(com.aioj.next.contract.contest.ContestAiPolicyMode.STRICT);
        contest.setAiPolicyNotes("no hints at all");
        ContestRunEntity run = run(401L, now.plusSeconds(3600), now.plusSeconds(7200), ContestRunStatus.DRAFT);
        ContestProblemEntity contestProblem = new ContestProblemEntity();
        contestProblem.setId(601L);
        contestProblem.setContestId(301L);
        contestProblem.setProblemId(1001L);
        contestProblem.setLabel("A");
        contestProblem.setDisplayTitle("Alpha");
        contestProblem.setScore(100);
        contestProblem.setSortOrder(0);
        ProblemEntity problem = new ProblemEntity();
        problem.setId(1001L);
        problem.setStatement("private statement");
        problem.setVisibility(com.aioj.next.contract.problem.ProblemVisibility.PRIVATE);
        when(contestMapper.selectById(301L)).thenReturn(contest);
        when(contestRunMapper.selectById(401L)).thenReturn(run);
        when(contestRunMapper.selectCount(any())).thenReturn(0L);
        when(contestProblemMapper.selectList(any())).thenReturn(List.of(contestProblem));
        when(scoringRuleMapper.selectOne(any())).thenReturn(null);
        when(problemCatalog.findActive(1001L)).thenReturn(java.util.Optional.of(problem));
        when(allowedGroupMapper.selectList(any())).thenReturn(List.of());

        var response = service.publishRun(301L, 401L);

        assertEquals(com.aioj.next.contract.contest.ContestAiPolicyMode.STRICT, response.aiPolicyModeSnapshot());
        assertEquals("no hints at all", response.aiPolicyNotesSnapshot());
        ArgumentCaptor<ContestRunProblemSnapshotEntity> snapshotCaptor = ArgumentCaptor.forClass(ContestRunProblemSnapshotEntity.class);
        verify(problemSnapshotMapper).insert(snapshotCaptor.capture());
        assertEquals(com.aioj.next.contract.problem.ProblemVisibility.PRIVATE, snapshotCaptor.getValue().getVisibility());
        assertEquals("private statement", snapshotCaptor.getValue().getStatement());
        verify(eventPublisher).publishEvent(any(ContestInvitationNotificationRequestedEvent.class));
    }

    @Test
    void inviteRejectsWhenParticipantLimitReached() {
        authenticate(7L, Role.TEACHER);
        Instant now = Instant.now();
        ContestEntity contest = contest(301L);
        ContestRunEntity run = run(401L, now.minusSeconds(3600), now.plusSeconds(3600), ContestRunStatus.SCHEDULED);
        run.setMaxParticipants(1);
        UserAccountEntity student = user(1001L, "1001", "Student 1001");
        when(contestMapper.selectById(301L)).thenReturn(contest);
        when(contestRunMapper.selectById(401L)).thenReturn(run);
        when(userAccountMapper.selectOne(any())).thenReturn(student);
        when(userAccountMapper.selectById(1001L)).thenReturn(student);
        when(participantMapper.selectOne(any())).thenReturn(null);
        when(participantMapper.selectCount(any())).thenReturn(1L);

        DomainException error = assertThrows(DomainException.class,
                () -> service.invite(301L, 401L, new ContestParticipantAddRequest(null, "1001")));

        assertEquals(ErrorCode.BAD_REQUEST, error.errorCode());
        assertEquals("Contest run participant limit reached", error.getMessage());
        verify(registrationMapper, never()).insert(any(ContestRegistrationEntity.class));
    }

    @Test
    void inviteRejectsUnknownAccountWithNotFound() {
        authenticate(7L, Role.TEACHER);
        Instant now = Instant.now();
        when(contestMapper.selectById(301L)).thenReturn(contest(301L));
        when(contestRunMapper.selectById(401L)).thenReturn(run(401L, now.minusSeconds(3600), now.plusSeconds(3600), ContestRunStatus.SCHEDULED));
        when(userAccountMapper.selectOne(any())).thenReturn(null);

        DomainException error = assertThrows(DomainException.class,
                () -> service.invite(301L, 401L, new ContestParticipantAddRequest(null, "missing")));

        assertEquals(ErrorCode.NOT_FOUND, error.errorCode());
        assertEquals("User not found", error.getMessage());
    }

    @Test
    void problemSnapshotsReturnTagArrayFromSnapshotJson() {
        authenticate(7L, Role.TEACHER);
        Instant now = Instant.now();
        when(contestMapper.selectById(301L)).thenReturn(contest(301L));
        when(contestRunMapper.selectById(401L)).thenReturn(run(401L, now.minusSeconds(3600), now.plusSeconds(3600), ContestRunStatus.SCHEDULED));
        ContestRunProblemSnapshotEntity snapshot = new ContestRunProblemSnapshotEntity();
        snapshot.setId(801L);
        snapshot.setContestId(301L);
        snapshot.setContestRunId(401L);
        snapshot.setContestProblemId(701L);
        snapshot.setProblemId(1001L);
        snapshot.setLabel("A");
        snapshot.setDisplayTitle("Dynamic Programming");
        snapshot.setStatement("Solve it");
        snapshot.setTags("[\"dp\",\"graph\",\"dp\"]");
        snapshot.setSortOrder(0);
        snapshot.setCreatedAt(now);
        when(problemSnapshotMapper.selectList(any())).thenReturn(List.of(snapshot));

        var response = service.problemSnapshots(301L, 401L);

        assertEquals(List.of("dp", "graph"), response.get(0).tags());
    }

    @Test
    void publishRejectsDuplicatePublishedRunTitleInActiveContest() {
        authenticate(7L, Role.TEACHER);
        Instant now = Instant.now();
        when(contestMapper.selectById(301L)).thenReturn(contest(301L));
        when(contestRunMapper.selectById(401L)).thenReturn(run(401L, now.plusSeconds(3600), now.plusSeconds(7200), ContestRunStatus.DRAFT));
        when(contestRunMapper.selectCount(any())).thenReturn(1L);

        DomainException error = assertThrows(DomainException.class, () -> service.publishRun(301L, 401L));

        assertEquals(ErrorCode.CONFLICT, error.errorCode());
        assertEquals("Contest run title already exists", error.getMessage());
        verify(problemSnapshotMapper, never()).insert(any(ContestRunProblemSnapshotEntity.class));
    }

    @Test
    void publishAllowsTitleWhenOnlyDeletedDuplicateRunExists() {
        authenticate(7L, Role.TEACHER);
        Instant now = Instant.now();
        when(contestMapper.selectById(301L)).thenReturn(contest(301L));
        when(contestRunMapper.selectById(401L)).thenReturn(run(401L, now.plusSeconds(3600), now.plusSeconds(7200), ContestRunStatus.DRAFT));
        when(contestRunMapper.selectCount(any())).thenReturn(0L);
        when(contestProblemMapper.selectList(any())).thenReturn(List.of());

        DomainException error = assertThrows(DomainException.class, () -> service.publishRun(301L, 401L));

        assertEquals(ErrorCode.BAD_REQUEST, error.errorCode());
        assertEquals("Contest run must have at least one problem", error.getMessage());
    }

    @Test
    void notStartedRunKeepsProblemsAndScoreboardHidden() {
        authenticate(9L, Role.STUDENT);
        Instant now = Instant.now();
        ContestEntity contest = contest(301L);
        ContestRunEntity run = run(401L, now.plusSeconds(3600), now.plusSeconds(7200), ContestRunStatus.SCHEDULED);
        when(contestMapper.selectById(301L)).thenReturn(contest);
        when(contestRunMapper.selectById(401L)).thenReturn(run);
        when(participantMapper.selectOne(any())).thenReturn(participant(401L, 9L));
        when(allowedGroupMapper.selectList(any())).thenReturn(List.of());
        when(contestProblemMapper.selectCount(any())).thenReturn(2L);

        var response = service.openRun(301L, 401L);

        assertFalse(response.canSubmit());
        assertFalse(response.canViewProblems());
        assertFalse(response.canViewScoreboard());
        DomainException error = assertThrows(DomainException.class, () -> service.problemSnapshots(301L, 401L));
        assertEquals(ErrorCode.FORBIDDEN, error.errorCode());
    }

    @Test
    void noRegistrationWindowRunIsVisibleBeforeStartButCannotJoinYet() {
        authenticate(9L, Role.STUDENT);
        Instant now = Instant.now();
        ContestEntity contest = contest(301L);
        ContestRunEntity run = run(401L, now.plusSeconds(3600), now.plusSeconds(7200), ContestRunStatus.SCHEDULED);
        when(contestMapper.selectById(301L)).thenReturn(contest);
        when(contestRunMapper.selectById(401L)).thenReturn(run);
        when(allowedGroupMapper.selectList(any())).thenReturn(List.of());
        when(participantMapper.selectOne(any())).thenReturn(null);
        when(contestProblemMapper.selectCount(any())).thenReturn(2L);

        var response = service.openRun(301L, 401L);

        assertFalse(response.canRegister());
        assertFalse(response.canSubmit());
        assertFalse(response.canViewProblems());
        verify(registrationMapper, never()).insert(any(ContestRegistrationEntity.class));
        verify(participantMapper, never()).insert(any(ContestParticipantEntity.class));
        verify(participantSnapshotMapper, never()).insert(any(ContestParticipantSnapshotEntity.class));
    }

    @Test
    void noRegistrationWindowRunCanJoinAfterStart() {
        authenticate(9L, Role.STUDENT);
        Instant now = Instant.now();
        ContestEntity contest = contest(301L);
        ContestRunEntity run = run(401L, now.minusSeconds(3600), now.plusSeconds(7200), ContestRunStatus.SCHEDULED);
        when(contestMapper.selectById(301L)).thenReturn(contest);
        when(contestRunMapper.selectById(401L)).thenReturn(run);
        when(allowedGroupMapper.selectList(any())).thenReturn(List.of());
        when(participantMapper.selectOne(any())).thenReturn(null);
        when(registrationMapper.selectOne(any())).thenReturn(null);
        when(contestProblemMapper.selectCount(any())).thenReturn(2L);

        var response = service.openRun(301L, 401L);

        assertTrue(response.canRegister());
        assertFalse(response.canSubmit());
        assertFalse(response.canViewProblems());
        verify(participantMapper, never()).insert(any(ContestParticipantEntity.class));
    }

    @Test
    void archivedRunIsNotOpenToStudents() {
        authenticate(9L, Role.STUDENT);
        Instant now = Instant.now();
        when(contestMapper.selectById(301L)).thenReturn(contest(301L));
        when(contestRunMapper.selectById(401L)).thenReturn(run(401L, now.minusSeconds(7200), now.minusSeconds(3600), ContestRunStatus.ARCHIVED));

        DomainException error = assertThrows(DomainException.class, () -> service.openRun(301L, 401L));

        assertEquals(ErrorCode.FORBIDDEN, error.errorCode());
    }

    private ContestEntity contest(Long id) {
        ContestEntity contest = new ContestEntity();
        contest.setId(id);
        contest.setOwnerUserId(7L);
        contest.setTitle("Spring Invitational");
        contest.setDescription("Practice contest");
        contest.setMode(ContestMode.ACM);
        contest.setStatus(ContestStatus.PUBLISHED);
        contest.setVisibility(ContestVisibility.GROUP);
        contest.setPenaltyMinutes(20);
        contest.setCePenalty(false);
        contest.setCreatedAt(Instant.now().minusSeconds(86400));
        contest.setUpdatedAt(Instant.now().minusSeconds(86400));
        return contest;
    }

    private ContestRunEntity run(Long id, Instant startAt, Instant endAt, ContestRunStatus status) {
        ContestRunEntity run = new ContestRunEntity();
        run.setId(id);
        run.setContestId(301L);
        run.setRunKind(ContestRunKind.FORMAL);
        run.setTitle("Final Run");
        run.setStatus(status);
        run.setStartAt(startAt);
        run.setEndAt(endAt);
        run.setRegistrationPolicy(ContestRegistrationPolicy.PUBLIC_SELF_REGISTER);
        run.setRegistrationAccess(ContestRegistrationAccess.PUBLIC);
        run.setApprovalRequired(false);
        run.setCreatedBy(7L);
        run.setCreatedAt(startAt.minusSeconds(3600));
        run.setUpdatedAt(startAt.minusSeconds(3600));
        return run;
    }

    private ContestParticipantEntity participant(Long runId, Long userId) {
        ContestParticipantEntity participant = new ContestParticipantEntity();
        participant.setId(501L);
        participant.setContestId(301L);
        participant.setContestRunId(runId);
        participant.setUserId(userId);
        participant.setParticipantType(ContestParticipantType.INDIVIDUAL);
        participant.setStatus(ContestParticipantStatus.ACTIVE);
        participant.setAccountSnapshot("student" + userId);
        participant.setDisplayNameSnapshot("Student " + userId);
        participant.setRegisteredAt(Instant.now().minusSeconds(3600));
        participant.setCreatedAt(Instant.now().minusSeconds(3600));
        participant.setUpdatedAt(Instant.now().minusSeconds(3600));
        return participant;
    }

    private ContestRegistrationEntity registration(Long id, Long runId, Long userId, ContestRegistrationStatus status) {
        ContestRegistrationEntity registration = new ContestRegistrationEntity();
        registration.setId(id);
        registration.setContestId(301L);
        registration.setContestRunId(runId);
        registration.setUserId(userId);
        registration.setStatus(status);
        registration.setRequestedAt(Instant.now().minusSeconds(3600));
        registration.setCreatedAt(Instant.now().minusSeconds(3600));
        registration.setUpdatedAt(Instant.now().minusSeconds(3600));
        return registration;
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
