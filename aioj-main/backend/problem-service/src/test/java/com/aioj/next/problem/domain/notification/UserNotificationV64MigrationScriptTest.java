package com.aioj.next.problem.domain.notification;

import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class UserNotificationV64MigrationScriptTest {
    @Test
    void v64CreatesDurableRecipientScopedNotificationLedger() throws Exception {
        String sql;
        try (InputStream stream = getClass().getClassLoader().getResourceAsStream(
                "db/migration/V64__user_notifications.sql")) {
            assertThat(stream).isNotNull();
            sql = new String(stream.readAllBytes(), StandardCharsets.UTF_8).toLowerCase();
        }

        assertThat(sql).contains(
                "create table user_notifications",
                "recipient_user_id bigint not null",
                "deduplication_key varchar(192) not null",
                "uk_user_notification_deduplication",
                "idx_user_notification_unread");
        assertThat(sql).doesNotContain("drop table", "delete from");
    }
}
