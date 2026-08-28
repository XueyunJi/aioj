package com.aioj.next.problem.domain.notification;

import com.aioj.next.common.security.Role;
import com.aioj.next.common.security.SecurityPrincipal;
import com.aioj.next.contract.notification.UserNotificationMarkReadRequest;
import com.aioj.next.contract.notification.UserNotificationType;
import com.aioj.next.problem.persistence.entity.UserNotificationEntity;
import com.aioj.next.problem.persistence.mapper.UserNotificationMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.time.Instant;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class UserNotificationServiceTest {
    @Mock
    private UserNotificationMapper notificationMapper;
    @Mock
    private ApplicationEventPublisher eventPublisher;

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    private void initializeTableMetadata() {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new Configuration(), "user-notification-test"),
                UserNotificationEntity.class);
    }

    @Test
    void newContestInvitationPersistsThenPublishesWakeUpEvent() {
        UserNotificationService service = new UserNotificationService(notificationMapper, eventPublisher);
        doAnswer(invocation -> {
            UserNotificationEntity entity = invocation.getArgument(0);
            entity.setId(7001L);
            return 1;
        }).when(notificationMapper).insert(any(UserNotificationEntity.class));

        service.createContestInvitation(101L, 701L, 401L, 2L, Instant.parse("2026-08-09T08:00:00Z"));

        ArgumentCaptor<UserNotificationEntity> notification = ArgumentCaptor.forClass(UserNotificationEntity.class);
        verify(notificationMapper).insert(notification.capture());
        assertThat(notification.getValue().getRecipientUserId()).isEqualTo(101L);
        assertThat(notification.getValue().getNotificationType()).isEqualTo(UserNotificationType.CONTEST_INVITATION);
        assertThat(notification.getValue().getSubjectId()).isEqualTo("701");
        assertThat(notification.getValue().getScopeId()).isEqualTo("401");
        assertThat(notification.getValue().getDeduplicationKey()).isEqualTo("contest-invitation:v2:701:2");
        verify(eventPublisher).publishEvent(any(UserNotificationCreatedEvent.class));
    }

    @Test
    void duplicateNotificationDoesNotPublishAnotherWakeUpEvent() {
        UserNotificationService service = new UserNotificationService(notificationMapper, eventPublisher);
        doThrow(new DuplicateKeyException("duplicate")).when(notificationMapper).insert(any(UserNotificationEntity.class));

        service.createContestInvitation(101L, 701L, 401L, 2L, Instant.parse("2026-08-09T08:00:00Z"));

        verify(eventPublisher, never()).publishEvent(any(UserNotificationCreatedEvent.class));
    }

    @Test
    void invitationUnreadCountUsesOnlyCurrentlyVisibleInviteRows() {
        authenticate(101L);
        UserNotificationService service = new UserNotificationService(notificationMapper, eventPublisher);
        when(notificationMapper.countVisibleUnreadContestInvitations(org.mockito.ArgumentMatchers.eq(101L), any()))
                .thenReturn(0L);

        var count = service.unreadCount(UserNotificationType.CONTEST_INVITATION);

        assertThat(count.count()).isZero();
        verify(notificationMapper).countVisibleUnreadContestInvitations(org.mockito.ArgumentMatchers.eq(101L), any());
        verify(notificationMapper, never()).selectCount(any());
    }

    @Test
    void listAndMarkReadAlwaysScopeToAuthenticatedRecipient() {
        initializeTableMetadata();
        authenticate(101L);
        UserNotificationService service = new UserNotificationService(notificationMapper, eventPublisher);
        UserNotificationEntity entity = new UserNotificationEntity();
        entity.setId(7001L);
        entity.setRecipientUserId(101L);
        entity.setNotificationType(UserNotificationType.CONTEST_INVITATION);
        entity.setSubjectType(UserNotificationService.SUBJECT_CONTEST_REGISTRATION);
        entity.setSubjectId("701");
        entity.setCreatedAt(Instant.now());
        Page<UserNotificationEntity> page = new Page<>(1, 20);
        page.setRecords(List.of(entity));
        page.setTotal(1);
        when(notificationMapper.selectPage(any(), any())).thenReturn(page);
        when(notificationMapper.update(org.mockito.ArgumentMatchers.isNull(), any())).thenReturn(1);

        var result = service.list(UserNotificationType.CONTEST_INVITATION, null, null, null, null, true, 1, 20);
        var marked = service.markCurrentUserRead(new UserNotificationMarkReadRequest(
                UserNotificationType.CONTEST_INVITATION,
                UserNotificationService.SUBJECT_CONTEST_REGISTRATION,
                "701"
        ));

        assertThat(result.records()).hasSize(1);
        assertThat(marked.markedCount()).isEqualTo(1);
        ArgumentCaptor<LambdaQueryWrapper<UserNotificationEntity>> listQuery = ArgumentCaptor.forClass(LambdaQueryWrapper.class);
        verify(notificationMapper).selectPage(any(), listQuery.capture());
        assertThat(listQuery.getValue().getSqlSegment()).contains("recipientUserId");
        ArgumentCaptor<LambdaUpdateWrapper<UserNotificationEntity>> updateQuery = ArgumentCaptor.forClass(LambdaUpdateWrapper.class);
        verify(notificationMapper).update(org.mockito.ArgumentMatchers.isNull(), updateQuery.capture());
        assertThat(updateQuery.getValue().getSqlSegment()).contains("recipientUserId");
    }

    private void authenticate(Long userId) {
        var principal = new SecurityPrincipal(userId, "student-" + userId, Set.of(Role.STUDENT));
        var authentication = new UsernamePasswordAuthenticationToken(principal, null,
                List.of(new SimpleGrantedAuthority("ROLE_STUDENT")));
        SecurityContextHolder.getContext().setAuthentication(authentication);
    }
}
