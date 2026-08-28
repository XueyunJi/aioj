package com.aioj.next.problem.domain;

import com.aioj.next.common.error.DomainException;
import com.aioj.next.common.error.ErrorCode;
import com.aioj.next.common.security.Role;
import com.aioj.next.common.security.SecurityPrincipal;
import com.aioj.next.contract.contest.ContestAnnouncementRequest;
import com.aioj.next.contract.contest.ContestAnnouncementStatus;
import com.aioj.next.contract.contest.ContestClarificationCreateRequest;
import com.aioj.next.contract.contest.ContestClarificationReplyRequest;
import com.aioj.next.contract.contest.ContestClarificationStatus;
import com.aioj.next.contract.contest.ContestClarificationVisibility;
import com.aioj.next.contract.contest.ContestMode;
import com.aioj.next.contract.contest.ContestParticipantStatus;
import com.aioj.next.contract.contest.ContestRegistrationAccess;
import com.aioj.next.contract.contest.ContestRunStatus;
import com.aioj.next.contract.contest.ContestStatus;
import com.aioj.next.contract.contest.ContestVisibility;
import com.aioj.next.problem.persistence.entity.ContestAnnouncementEntity;
import com.aioj.next.problem.persistence.entity.ContestClarificationEntity;
import com.aioj.next.problem.persistence.entity.ContestEntity;
import com.aioj.next.problem.persistence.entity.ContestParticipantEntity;
import com.aioj.next.problem.persistence.entity.ContestRunEntity;
import com.aioj.next.problem.persistence.mapper.ContestAnnouncementMapper;
import com.aioj.next.problem.persistence.mapper.ContestClarificationMapper;
import com.aioj.next.problem.persistence.mapper.ContestParticipantMapper;
import com.aioj.next.problem.persistence.mapper.ContestRegistrationMapper;
import com.aioj.next.problem.persistence.mapper.ContestRunAllowedGroupMapper;
import com.aioj.next.problem.persistence.mapper.ContestRunProblemSnapshotMapper;
import com.aioj.next.problem.persistence.mapper.LearningGroupMemberMapper;
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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ContestCommunicationServiceTest {
    @Mock
    private ContestRunService contestRunService;
    @Mock
    private ContestAnnouncementMapper announcementMapper;
    @Mock
    private ContestClarificationMapper clarificationMapper;
    @Mock
    private ContestParticipantMapper participantMapper;
    @Mock
    private ContestRegistrationMapper registrationMapper;
    @Mock
    private ContestRunAllowedGroupMapper allowedGroupMapper;
    @Mock
    private LearningGroupMemberMapper learningGroupMemberMapper;
    @Mock
    private ContestRunProblemSnapshotMapper problemSnapshotMapper;
    @Mock
    private ContestProblemVisibilityService visibilityService;

    private ContestCommunicationService service;

    @BeforeEach
    void setUp() {
        org.mockito.Mockito.lenient().when(visibilityService.hiddenContestProblemIdsForRun(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any()))
                .thenReturn(java.util.Set.of());
        service = new ContestCommunicationService(contestRunService, announcementMapper, clarificationMapper,
                participantMapper, registrationMapper, allowedGroupMapper, learningGroupMemberMapper,
                problemSnapshotMapper, visibilityService);
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void teacherCreatesPublishedAnnouncement() {
        authenticate(7L, Role.TEACHER);
        ContestEntity contest = contest(301L);
        ContestRunEntity run = activeRun(401L);
        when(contestRunService.requireContest(301L)).thenReturn(contest);
        when(contestRunService.requireRun(301L, 401L)).thenReturn(run);
        when(contestRunService.canManage(contest)).thenReturn(true);
        doAnswer(invocation -> {
            ContestAnnouncementEntity entity = invocation.getArgument(0);
            entity.setId(501L);
            return 1;
        }).when(announcementMapper).insert(any(ContestAnnouncementEntity.class));

        var response = service.createAnnouncement(301L, 401L,
                new ContestAnnouncementRequest("  Important  ", "Read the statement update.", true));

        assertEquals(501L, response.id());
        assertEquals("Important", response.title());
        assertEquals(ContestAnnouncementStatus.PUBLISHED, response.status());
        assertTrue(response.pinned());
        ArgumentCaptor<ContestAnnouncementEntity> captor = ArgumentCaptor.forClass(ContestAnnouncementEntity.class);
        verify(announcementMapper).insert(captor.capture());
        assertEquals(7L, captor.getValue().getAuthorUserId());
    }

    @Test
    void studentCanAskDuringActiveRun() {
        authenticate(91L, Role.STUDENT);
        ContestEntity contest = contest(301L);
        ContestRunEntity run = activeRun(401L);
        when(contestRunService.requireContest(301L)).thenReturn(contest);
        when(contestRunService.requireRun(301L, 401L)).thenReturn(run);
        when(participantMapper.selectOne(any())).thenReturn(activeParticipant(601L, 401L, 91L));
        doAnswer(invocation -> {
            ContestClarificationEntity entity = invocation.getArgument(0);
            entity.setId(701L);
            return 1;
        }).when(clarificationMapper).insert(any(ContestClarificationEntity.class));

        var response = service.createClarification(301L, 401L,
                new ContestClarificationCreateRequest(null, "Can we assume sorted input?"));

        assertEquals(701L, response.id());
        assertEquals(ContestClarificationStatus.OPEN, response.status());
        assertTrue(response.mine());
        assertEquals(91L, response.userId());
    }

    @Test
    void studentCannotAskBeforeRunStarts() {
        authenticate(91L, Role.STUDENT);
        ContestEntity contest = contest(301L);
        ContestRunEntity run = activeRun(401L);
        run.setStartAt(Instant.now().plusSeconds(3600));
        run.setEndAt(Instant.now().plusSeconds(7200));
        when(contestRunService.requireContest(301L)).thenReturn(contest);
        when(contestRunService.requireRun(301L, 401L)).thenReturn(run);
        when(participantMapper.selectOne(any())).thenReturn(activeParticipant(601L, 401L, 91L));

        DomainException error = assertThrows(DomainException.class,
                () -> service.createClarification(301L, 401L,
                        new ContestClarificationCreateRequest(null, "Can I ask now?")));

        assertEquals(ErrorCode.FORBIDDEN, error.errorCode());
    }

    @Test
    void publicClarificationHidesAskerIdentityFromOtherStudents() {
        authenticate(92L, Role.STUDENT);
        ContestEntity contest = contest(301L);
        ContestRunEntity run = activeRun(401L);
        ContestClarificationEntity entity = clarification(701L, 401L, 91L, 601L,
                ContestClarificationVisibility.PUBLIC);
        Page<ContestClarificationEntity> page = new Page<>(1, 20);
        page.setRecords(List.of(entity));
        page.setTotal(1);
        when(contestRunService.requireContest(301L)).thenReturn(contest);
        when(contestRunService.requireRun(301L, 401L)).thenReturn(run);
        when(clarificationMapper.selectPage(any(), any())).thenReturn(page);

        var response = service.listClarifications(301L, 401L, null, null, null, false, 1, 20);

        assertEquals(1, response.total());
        var item = response.records().get(0);
        assertFalse(item.mine());
        assertTrue(item.publicAnswer());
        assertNull(item.userId());
        assertNull(item.participantId());
        assertEquals("Official answer", item.answer());
    }

    @Test
    void teacherReplyCanPublishClarification() {
        authenticate(7L, Role.TEACHER);
        ContestEntity contest = contest(301L);
        ContestRunEntity run = activeRun(401L);
        ContestClarificationEntity entity = clarification(701L, 401L, 91L, 601L, null);
        when(contestRunService.requireContest(301L)).thenReturn(contest);
        when(contestRunService.requireRun(301L, 401L)).thenReturn(run);
        when(contestRunService.canManage(contest)).thenReturn(true);
        when(clarificationMapper.selectById(701L)).thenReturn(entity);

        var response = service.replyClarification(301L, 401L, 701L,
                new ContestClarificationReplyRequest("Use long long.", ContestClarificationVisibility.PUBLIC));

        assertEquals(ContestClarificationStatus.ANSWERED, response.status());
        assertEquals(ContestClarificationVisibility.PUBLIC, response.answerVisibility());
        assertEquals(7L, response.answeredBy());
        assertEquals("Use long long.", response.answer());
        verify(clarificationMapper).updateById(entity);
    }

    private ContestEntity contest(Long id) {
        ContestEntity contest = new ContestEntity();
        contest.setId(id);
        contest.setOwnerUserId(7L);
        contest.setTitle("Contest");
        contest.setMode(ContestMode.ACM);
        contest.setStatus(ContestStatus.PUBLISHED);
        contest.setVisibility(ContestVisibility.GROUP);
        return contest;
    }

    private ContestRunEntity activeRun(Long id) {
        ContestRunEntity run = new ContestRunEntity();
        run.setId(id);
        run.setContestId(301L);
        run.setTitle("Round");
        run.setStatus(ContestRunStatus.RUNNING);
        run.setRegistrationAccess(ContestRegistrationAccess.PUBLIC);
        run.setStartAt(Instant.now().minusSeconds(3600));
        run.setEndAt(Instant.now().plusSeconds(3600));
        return run;
    }

    private ContestParticipantEntity activeParticipant(Long id, Long runId, Long userId) {
        ContestParticipantEntity participant = new ContestParticipantEntity();
        participant.setId(id);
        participant.setContestId(301L);
        participant.setContestRunId(runId);
        participant.setUserId(userId);
        participant.setStatus(ContestParticipantStatus.ACTIVE);
        return participant;
    }

    private ContestClarificationEntity clarification(Long id, Long runId, Long userId, Long participantId,
                                                     ContestClarificationVisibility visibility) {
        Instant now = Instant.now();
        ContestClarificationEntity entity = new ContestClarificationEntity();
        entity.setId(id);
        entity.setContestId(301L);
        entity.setContestRunId(runId);
        entity.setUserId(userId);
        entity.setParticipantId(participantId);
        entity.setQuestion("Is the input sorted?");
        entity.setStatus(visibility == null ? ContestClarificationStatus.OPEN : ContestClarificationStatus.ANSWERED);
        entity.setAnswer(visibility == null ? null : "Official answer");
        entity.setAnswerVisibility(visibility);
        entity.setCreatedAt(now);
        entity.setUpdatedAt(now);
        return entity;
    }

    private void authenticate(Long userId, Role... roles) {
        Set<Role> roleSet = Set.of(roles);
        SecurityPrincipal principal = new SecurityPrincipal(userId, "user-" + userId, roleSet);
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(principal, "N/A",
                roleSet.stream().map(role -> new SimpleGrantedAuthority("ROLE_" + role.name())).toList()));
    }
}
