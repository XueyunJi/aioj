package com.aioj.next.problem.persistence.mapper;

import com.aioj.next.problem.persistence.entity.UserNotificationEntity;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.Instant;

public interface UserNotificationMapper extends BaseMapper<UserNotificationEntity> {
    @Select("""
            SELECT COUNT(*)
            FROM user_notifications notification
            JOIN contest_registrations registration
              ON notification.subject_type = 'CONTEST_REGISTRATION'
             AND notification.subject_id = CAST(registration.id AS CHAR)
            JOIN contest_runs run ON run.id = registration.contest_run_id
            JOIN contests contest ON contest.id = registration.contest_id
            WHERE notification.recipient_user_id = #{recipientUserId}
              AND notification.notification_type = 'CONTEST_INVITATION'
              AND notification.read_at IS NULL
              AND registration.user_id = #{recipientUserId}
              AND registration.status = 'INVITED'
              AND contest.deleted_at IS NULL
              AND contest.status = 'PUBLISHED'
              AND run.deleted_at IS NULL
              AND run.status NOT IN ('DRAFT', 'ARCHIVED')
              AND run.end_at > #{now}
            """)
    Long countVisibleUnreadContestInvitations(@Param("recipientUserId") Long recipientUserId,
                                              @Param("now") Instant now);
}
