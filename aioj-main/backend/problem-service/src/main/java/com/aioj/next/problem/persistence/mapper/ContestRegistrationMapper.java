package com.aioj.next.problem.persistence.mapper;

import com.aioj.next.problem.persistence.entity.ContestRegistrationEntity;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.Instant;
import java.util.List;

@Mapper
public interface ContestRegistrationMapper extends BaseMapper<ContestRegistrationEntity> {
    @Select("""
            SELECT registration.*
            FROM contest_registrations registration
            JOIN contest_runs run ON run.id = registration.contest_run_id
            JOIN contests contest ON contest.id = registration.contest_id
            WHERE registration.user_id = #{userId}
              AND registration.status = 'INVITED'
              AND contest.deleted_at IS NULL
              AND contest.status = 'PUBLISHED'
              AND run.deleted_at IS NULL
              AND run.status NOT IN ('DRAFT', 'ARCHIVED')
              AND run.end_at > #{now}
            ORDER BY registration.requested_at DESC, registration.id DESC
            """)
    Page<ContestRegistrationEntity> selectVisibleInvitedPage(Page<ContestRegistrationEntity> page,
                                                              @Param("userId") Long userId,
                                                              @Param("now") Instant now);

    @Select("""
            SELECT registration.*
            FROM contest_registrations registration
            JOIN contest_runs run ON run.id = registration.contest_run_id
            JOIN contests contest ON contest.id = registration.contest_id
            WHERE registration.contest_run_id = #{contestRunId}
              AND registration.status = 'INVITED'
              AND registration.invitation_notification_delivered_version < registration.invitation_notification_version
              AND contest.deleted_at IS NULL
              AND contest.status = 'PUBLISHED'
              AND run.deleted_at IS NULL
              AND run.status NOT IN ('DRAFT', 'ARCHIVED')
              AND run.end_at > #{now}
            ORDER BY registration.requested_at ASC, registration.id ASC
            LIMIT #{limit}
            """)
    List<ContestRegistrationEntity> selectPendingInvitationNotificationsForRun(@Param("contestRunId") Long contestRunId,
                                                                                 @Param("now") Instant now,
                                                                                 @Param("limit") int limit);

    @Select("""
            SELECT registration.*
            FROM contest_registrations registration
            JOIN contest_runs run ON run.id = registration.contest_run_id
            JOIN contests contest ON contest.id = registration.contest_id
            WHERE registration.status = 'INVITED'
              AND registration.invitation_notification_delivered_version < registration.invitation_notification_version
              AND contest.deleted_at IS NULL
              AND contest.status = 'PUBLISHED'
              AND run.deleted_at IS NULL
              AND run.status NOT IN ('DRAFT', 'ARCHIVED')
              AND run.end_at > #{now}
            ORDER BY registration.requested_at ASC, registration.id ASC
            LIMIT #{limit}
            """)
    List<ContestRegistrationEntity> selectPendingInvitationNotifications(@Param("now") Instant now,
                                                                          @Param("limit") int limit);

    @Update("""
            UPDATE contest_registrations
            SET invitation_notification_delivered_version = #{version},
                updated_at = #{updatedAt}
            WHERE id = #{registrationId}
              AND status = 'INVITED'
              AND invitation_notification_version = #{version}
              AND invitation_notification_delivered_version < #{version}
            """)
    int markInvitationNotificationDelivered(@Param("registrationId") Long registrationId,
                                             @Param("version") long version,
                                             @Param("updatedAt") Instant updatedAt);
}
