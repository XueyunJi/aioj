package com.aioj.next.ai.domain.migration;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Result of one legacy memory migration run (dry-run or apply). Counters are
 * authoritative; entries carry the per-row detail for everything that is not a
 * plain "already mapped" skip, so the dual-run comparison can be eyeballed from
 * the log. The profile/weakness/signals sections are reconciliation-only reads
 * (M-Q7): the migration never writes to those tables.
 */
public class LegacyMemoryMigrationReport {

    public enum Action {
        /** Dry-run only: this legacy row would be migrated by an apply run. */
        WOULD_MIGRATE,
        /** Apply run: a new claim was inserted for this legacy row. */
        MIGRATED,
        /** At least one claim already references this legacy row; untouched. */
        ALREADY_MAPPED,
        /** memory_type=content rows are not user memories (M-Q4a); never migrated. */
        SKIPPED_CONTENT_TYPE,
        /** Unmapped legacy row whose memory_type has no category mapping. */
        SKIPPED_UNKNOWN_TYPE,
        /** Insert collided with the claims unique key; row skipped, needs manual review. */
        KEY_CONFLICT,
        /** Legacy row is SUPERSEDED but a linked claim is still ACTIVE; report only (M-Q3). */
        STATUS_MISMATCH
    }

    public record Entry(long legacyMemoryId, Long userId, Action action, String detail) {
    }

    private final boolean apply;
    private long scanned;
    private long migrated;
    private long alreadyMapped;
    private long skippedContentType;
    private long skippedUnknownType;
    private long keyConflicts;
    private long statusMismatches;
    private final List<Entry> entries = new ArrayList<>();
    private final Map<String, Long> profileByState = new LinkedHashMap<>();
    private final Map<String, Long> weaknessByStatus = new LinkedHashMap<>();
    private final Map<String, Long> signalsByStatus = new LinkedHashMap<>();

    public LegacyMemoryMigrationReport(boolean apply) {
        this.apply = apply;
    }

    public boolean isApply() {
        return apply;
    }

    public long getScanned() {
        return scanned;
    }

    /** Inserted (apply) or would-be-inserted (dry-run) claim count. */
    public long getMigrated() {
        return migrated;
    }

    public long getAlreadyMapped() {
        return alreadyMapped;
    }

    public long getSkippedContentType() {
        return skippedContentType;
    }

    public long getSkippedUnknownType() {
        return skippedUnknownType;
    }

    public long getKeyConflicts() {
        return keyConflicts;
    }

    public long getStatusMismatches() {
        return statusMismatches;
    }

    public List<Entry> getEntries() {
        return entries;
    }

    public Map<String, Long> getProfileByState() {
        return profileByState;
    }

    public Map<String, Long> getWeaknessByStatus() {
        return weaknessByStatus;
    }

    public Map<String, Long> getSignalsByStatus() {
        return signalsByStatus;
    }

    void recordScanned() {
        scanned++;
    }

    void recordMigrated(Entry entry) {
        migrated++;
        entries.add(entry);
    }

    void recordAlreadyMapped() {
        alreadyMapped++;
    }

    void recordSkippedContent(Entry entry) {
        skippedContentType++;
        entries.add(entry);
    }

    void recordSkippedUnknownType(Entry entry) {
        skippedUnknownType++;
        entries.add(entry);
    }

    void recordKeyConflict(Entry entry) {
        keyConflicts++;
        entries.add(entry);
    }

    void recordStatusMismatch(Entry entry) {
        statusMismatches++;
        entries.add(entry);
    }

    void recordProfileState(String state) {
        profileByState.merge(state == null ? "NULL" : state, 1L, Long::sum);
    }

    void recordWeaknessStatus(String status) {
        weaknessByStatus.merge(status == null ? "NULL" : status, 1L, Long::sum);
    }

    void recordSignalStatus(String status) {
        signalsByStatus.merge(status == null ? "NULL" : status, 1L, Long::sum);
    }

    public String summaryLine() {
        return String.format(
                "mode=%s scanned=%d %s=%d alreadyMapped=%d skippedContent=%d skippedUnknownType=%d keyConflicts=%d statusMismatches=%d profileByState=%s weaknessByStatus=%s signalsByStatus=%s",
                apply ? "apply" : "dry-run",
                scanned,
                apply ? "migrated" : "wouldMigrate",
                migrated,
                alreadyMapped,
                skippedContentType,
                skippedUnknownType,
                keyConflicts,
                statusMismatches,
                profileByState,
                weaknessByStatus,
                signalsByStatus);
    }
}
