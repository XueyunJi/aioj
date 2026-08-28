-- Invitation delivery is versioned separately from registration status so a
-- draft can retain selected invitees without becoming student-visible.
ALTER TABLE contest_registrations
    ADD COLUMN invitation_notification_version BIGINT NOT NULL DEFAULT 0,
    ADD COLUMN invitation_notification_delivered_version BIGINT NOT NULL DEFAULT 0,
    ADD INDEX idx_contest_registrations_invitation_delivery
        (status, invitation_notification_delivered_version, invitation_notification_version, contest_run_id);

-- Existing pending invitations remain intact. Published-run rows that already
-- have a V64 notification are considered delivered; draft rows are deliberately
-- left pending so publication creates one fresh V65 notification.
UPDATE contest_registrations registration
JOIN contest_runs run ON run.id = registration.contest_run_id
SET registration.invitation_notification_version = CASE
        WHEN registration.status = 'INVITED' THEN 1
        ELSE 0
    END,
    registration.invitation_notification_delivered_version = CASE
        WHEN registration.status = 'INVITED'
             AND run.status <> 'DRAFT'
             AND EXISTS (
                 SELECT 1
                 FROM user_notifications notification
                 WHERE notification.recipient_user_id = registration.user_id
                   AND notification.notification_type = 'CONTEST_INVITATION'
                   AND notification.subject_type = 'CONTEST_REGISTRATION'
                   AND notification.subject_id = CAST(registration.id AS CHAR)
             )
            THEN 1
        ELSE 0
    END;
