package com.aioj.next.ai.domain;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Text-level safety checks for the additive V63 script. A separate MySQL
 * execution preflight remains required before applying it to a shared database.
 */
class ContestAiAssistanceV63MigrationScriptTest {

    @Test
    void v63CreatesLedgerAndLegacySnapshotWithoutMutatingLegacySourceTables() throws IOException {
        String sql;
        try (InputStream stream = getClass().getClassLoader().getResourceAsStream(
                "db/migration/V63__contest_ai_assistance_statistics.sql")) {
            assertThat(stream).isNotNull();
            sql = new String(stream.readAllBytes(), StandardCharsets.UTF_8).toLowerCase();
        }

        assertThat(sql).contains(
                "create table ai_contest_assistance_turns",
                "create table ai_contest_assistance_model_usages",
                "create table ai_contest_assistance_legacy_snapshots",
                "insert into ai_contest_assistance_legacy_snapshots",
                "from ai_usage_records usage_record",
                "from ai_usage_records",
                "from operation_audit_events");
        assertThat(sql).doesNotContainPattern("\\bfrom\\s+ai_usage_records\\s+usage\\b");
        assertThat(sql).doesNotContain(
                "drop table",
                "delete from ai_usage_records",
                "delete from ai_conversations",
                "delete from ai_messages",
                "alter table ai_usage_records",
                "alter table ai_conversations",
                "alter table operation_audit_events");
    }
}
