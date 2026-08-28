package com.aioj.next.ai.domain.migration;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/**
 * One-shot trigger for the Agent Core V3 round-M legacy memory migration.
 * Disabled by default; set application property
 * {@code ai.legacy-memory-migration.mode} (or env var
 * {@code AI_LEGACY_MEMORY_MIGRATION_MODE}) to {@code dry-run} or {@code apply}
 * and start ai-service once. The report is printed to the boot log; unset the
 * flag afterwards. Safe to run repeatedly — the service is idempotent.
 */
@Component
public class LegacyMemoryMigrationRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(LegacyMemoryMigrationRunner.class);

    private final LegacyMemoryMigrationService migrationService;
    private final String mode;

    public LegacyMemoryMigrationRunner(
            LegacyMemoryMigrationService migrationService,
            @Value("${ai.legacy-memory-migration.mode:${AI_LEGACY_MEMORY_MIGRATION_MODE:off}}") String mode) {
        this.migrationService = migrationService;
        this.mode = mode == null ? "off" : mode.trim();
    }

    @Override
    public void run(ApplicationArguments args) {
        switch (mode) {
            case "off", "" -> log.debug("legacy memory migration disabled (mode={})", mode);
            case "dry-run" -> runAndLog(false);
            case "apply" -> runAndLog(true);
            default -> log.warn("legacy memory migration mode={} is not one of off|dry-run|apply; doing nothing", mode);
        }
    }

    private void runAndLog(boolean apply) {
        LegacyMemoryMigrationReport report = migrationService.migrate(apply);
        log.info("legacy memory migration report: {}", report.summaryLine());
        for (LegacyMemoryMigrationReport.Entry entry : report.getEntries()) {
            log.info("legacy memory migration entry legacyId={} userId={} action={} detail={}",
                    entry.legacyMemoryId(), entry.userId(), entry.action(), entry.detail());
        }
    }
}
