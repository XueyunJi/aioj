package com.aioj.next.problem.domain.notification;

import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class ContestInvitationV65MigrationScriptTest {
    @Test
    void v65AddsVersionedDeliveryStateWithoutDeletingInvitationHistory() throws Exception {
        String sql;
        try (InputStream stream = getClass().getClassLoader().getResourceAsStream(
                "db/migration/V65__contest_invitation_notification_delivery.sql")) {
            assertThat(stream).isNotNull();
            sql = new String(stream.readAllBytes(), StandardCharsets.UTF_8).toLowerCase();
        }

        assertThat(sql).contains(
                "add column invitation_notification_version",
                "add column invitation_notification_delivered_version",
                "idx_contest_registrations_invitation_delivery",
                "user_notifications",
                "contest_invitation");
        assertThat(sql).doesNotContain("drop table", "delete from", "truncate table");
    }
}
