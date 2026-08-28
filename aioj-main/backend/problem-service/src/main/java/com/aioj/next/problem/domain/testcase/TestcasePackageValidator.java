package com.aioj.next.problem.domain.testcase;

import com.aioj.next.common.error.DomainException;
import com.aioj.next.common.error.ErrorCode;
import com.aioj.next.contract.problem.TestcaseCheckerProtocol;
import com.aioj.next.contract.problem.TestcaseCheckerType;
import com.aioj.next.problem.config.TestcaseProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipException;

@Component
public class TestcasePackageValidator {
    private static final String MANIFEST_PATH = "manifest.json";
    private static final long MANIFEST_MAX_BYTES = 1_048_576L;
    private static final int BUFFER_SIZE = 1024 * 1024;

    private final TestcaseProperties properties;
    private final ObjectMapper objectMapper;

    public TestcasePackageValidator(TestcaseProperties properties, ObjectMapper objectMapper) {
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    public ValidatedPackage validate(Path zipPath) {
        rejectSymlinksBestEffort(zipPath);
        try (ZipFile zipFile = new ZipFile(zipPath.toFile())) {
            ZipScan scan = scanEntries(zipFile);
            ZipEntryInfo manifestEntry = scan.entries().get(MANIFEST_PATH);
            if (manifestEntry == null || manifestEntry.directory()) {
                throw new DomainException(ErrorCode.BAD_REQUEST, "Testcase package manifest.json is required");
            }
            ManifestPayload manifest = objectMapper.readValue(scan.manifestBytes(), ManifestPayload.class);
            if (!StringUtils.hasText(manifest.version())) {
                throw new DomainException(ErrorCode.BAD_REQUEST, "Testcase manifest version is required");
            }
            if (manifest.version().length() > 64) {
                throw new DomainException(ErrorCode.BAD_REQUEST, "Testcase manifest version is too long");
            }
            if (manifest.cases() == null || manifest.cases().isEmpty()) {
                throw new DomainException(ErrorCode.BAD_REQUEST, "Testcase manifest cases are required");
            }
            ValidatedChecker checker = validateChecker(manifest.checker(), scan.entries());
            List<ValidatedSubtask> subtasks = validateSubtasks(manifest.subtasks());
            Set<String> subtaskKeys = new HashSet<>();
            for (ValidatedSubtask subtask : subtasks) {
                subtaskKeys.add(subtask.key());
            }

            List<ValidatedCase> cases = new ArrayList<>();
            for (int i = 0; i < manifest.cases().size(); i++) {
                ManifestCase manifestCase = manifest.cases().get(i);
                cases.add(validateCase(manifestCase, scan.entries(), subtaskKeys, !subtasks.isEmpty(), i));
            }
            String manifestJson = objectMapper.writeValueAsString(manifest);
            return new ValidatedPackage(manifest.version().trim(), manifestJson, checker, subtasks, cases);
        } catch (DomainException ex) {
            throw ex;
        } catch (ZipException ex) {
            throw new DomainException(ErrorCode.BAD_REQUEST, "Invalid testcase zip package");
        } catch (IOException ex) {
            throw new DomainException(ErrorCode.BAD_REQUEST, "Failed to read testcase zip package");
        }
    }

    private ZipScan scanEntries(ZipFile zipFile) throws IOException {
        Map<String, ZipEntryInfo> entries = new HashMap<>();
        byte[] manifestBytes = null;
        long totalUncompressedBytes = 0L;
        int entryCount = 0;
        Enumeration<? extends ZipEntry> enumeration = zipFile.entries();
        while (enumeration.hasMoreElements()) {
            ZipEntry entry = enumeration.nextElement();
            entryCount++;
            if (entryCount > properties.getMaxEntryCount()) {
                throw new DomainException(ErrorCode.BAD_REQUEST, "Testcase package has too many entries");
            }
            String path = normalizeZipPath(entry.getName());
            if (entries.containsKey(path)) {
                throw new DomainException(ErrorCode.BAD_REQUEST, "Duplicate testcase zip entry: " + path);
            }
            long size = entry.isDirectory() ? 0L : readEntry(zipFile, entry, MANIFEST_PATH.equals(path), totalUncompressedBytes);
            totalUncompressedBytes += size;
            if (totalUncompressedBytes > properties.getMaxUncompressedBytes()) {
                throw new DomainException(ErrorCode.BAD_REQUEST, "Testcase package is too large after decompression");
            }
            entries.put(path, new ZipEntryInfo(path, entry.isDirectory(), size));
            if (MANIFEST_PATH.equals(path) && !entry.isDirectory()) {
                manifestBytes = readManifest(zipFile, entry);
            }
        }
        return new ZipScan(entries, manifestBytes);
    }

    private long readEntry(ZipFile zipFile, ZipEntry entry, boolean manifest, long previousBytes) throws IOException {
        long maxEntryBytes = manifest ? MANIFEST_MAX_BYTES : properties.getMaxCaseFileBytes();
        long size = 0L;
        byte[] buffer = new byte[BUFFER_SIZE];
        try (InputStream input = zipFile.getInputStream(entry)) {
            int read;
            while ((read = input.read(buffer)) != -1) {
                size += read;
                if (size > maxEntryBytes) {
                    throw new DomainException(ErrorCode.BAD_REQUEST, "Testcase zip entry is too large: " + entry.getName());
                }
                if (previousBytes + size > properties.getMaxUncompressedBytes()) {
                    throw new DomainException(ErrorCode.BAD_REQUEST, "Testcase package is too large after decompression");
                }
            }
        }
        return size;
    }

    private byte[] readManifest(ZipFile zipFile, ZipEntry entry) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[16 * 1024];
        long size = 0L;
        try (InputStream input = zipFile.getInputStream(entry)) {
            int read;
            while ((read = input.read(buffer)) != -1) {
                size += read;
                if (size > MANIFEST_MAX_BYTES) {
                    throw new DomainException(ErrorCode.BAD_REQUEST, "Testcase manifest.json is too large");
                }
                output.write(buffer, 0, read);
            }
        }
        return output.toByteArray();
    }

    private ValidatedCase validateCase(ManifestCase manifestCase, Map<String, ZipEntryInfo> entries,
                                       Set<String> subtaskKeys, boolean requireKnownSubtask, int sortOrder) {
        if (manifestCase == null) {
            throw new DomainException(ErrorCode.BAD_REQUEST, "Testcase manifest contains an empty case");
        }
        String input = normalizeZipPath(manifestCase.input());
        String output = normalizeZipPath(manifestCase.output());
        ZipEntryInfo inputEntry = requireFile(entries, input, "input");
        ZipEntryInfo outputEntry = requireFile(entries, output, "output");
        String name = StringUtils.hasText(manifestCase.name()) ? manifestCase.name().trim() : "case-" + (sortOrder + 1);
        if (name.length() > 160) {
            throw new DomainException(ErrorCode.BAD_REQUEST, "Testcase name is too long");
        }
        String subtaskKey = normalizeOptionalSubtaskKey(manifestCase.subtaskKey());
        if (requireKnownSubtask) {
            if (!StringUtils.hasText(subtaskKey)) {
                throw new DomainException(ErrorCode.BAD_REQUEST, "Testcase subtask key is required when subtasks are declared");
            }
            if (!subtaskKeys.contains(subtaskKey)) {
                throw new DomainException(ErrorCode.BAD_REQUEST, "Unknown testcase subtask key: " + subtaskKey);
            }
        }
        return new ValidatedCase(name, input, output, Boolean.TRUE.equals(manifestCase.sample()),
                subtaskKey, manifestCase.score(), inputEntry.sizeBytes(), outputEntry.sizeBytes(), sortOrder);
    }

    private ValidatedChecker validateChecker(ManifestChecker checker, Map<String, ZipEntryInfo> entries) {
        if (checker == null || !StringUtils.hasText(checker.type())) {
            return new ValidatedChecker(TestcaseCheckerType.STANDARD, null, null, null);
        }
        TestcaseCheckerType type;
        try {
            type = TestcaseCheckerType.valueOf(checker.type().trim().toUpperCase());
        } catch (IllegalArgumentException ex) {
            throw new DomainException(ErrorCode.BAD_REQUEST, "Unsupported testcase checker type: " + checker.type());
        }
        if (type == TestcaseCheckerType.STANDARD) {
            return new ValidatedChecker(TestcaseCheckerType.STANDARD, null, null, null);
        }
        String language = checker.language() == null ? "" : checker.language().trim().toLowerCase();
        if (!"cpp".equals(language)) {
            throw new DomainException(ErrorCode.BAD_REQUEST, "Custom testcase checker language must be cpp");
        }
        String source = normalizeZipPath(checker.source());
        requireFile(entries, source, "checker source");
        TestcaseCheckerProtocol protocol;
        try {
            protocol = StringUtils.hasText(checker.protocol())
                    ? TestcaseCheckerProtocol.valueOf(checker.protocol().trim().toUpperCase())
                    : TestcaseCheckerProtocol.AIOJ_JSON;
        } catch (IllegalArgumentException ex) {
            throw new DomainException(ErrorCode.BAD_REQUEST, "Unsupported testcase checker protocol: " + checker.protocol());
        }
        if (protocol != TestcaseCheckerProtocol.AIOJ_JSON) {
            throw new DomainException(ErrorCode.BAD_REQUEST, "Custom testcase checker protocol must be AIOJ_JSON");
        }
        return new ValidatedChecker(type, language, source, protocol);
    }

    private List<ValidatedSubtask> validateSubtasks(List<ManifestSubtask> subtasks) {
        if (subtasks == null || subtasks.isEmpty()) {
            return List.of();
        }
        List<ValidatedSubtask> validated = new ArrayList<>();
        Set<String> keys = new HashSet<>();
        for (int i = 0; i < subtasks.size(); i++) {
            ManifestSubtask subtask = subtasks.get(i);
            if (subtask == null) {
                throw new DomainException(ErrorCode.BAD_REQUEST, "Testcase manifest contains an empty subtask");
            }
            String key = normalizeOptionalSubtaskKey(subtask.key());
            if (!StringUtils.hasText(key)) {
                throw new DomainException(ErrorCode.BAD_REQUEST, "Subtask key is required");
            }
            if (!keys.add(key)) {
                throw new DomainException(ErrorCode.BAD_REQUEST, "Duplicate subtask key: " + key);
            }
            String title = StringUtils.hasText(subtask.title()) ? subtask.title().trim() : key;
            if (title.length() > 160) {
                throw new DomainException(ErrorCode.BAD_REQUEST, "Subtask title is too long");
            }
            BigDecimal score = subtask.score() == null ? BigDecimal.ZERO : subtask.score();
            if (score.compareTo(BigDecimal.ZERO) < 0) {
                throw new DomainException(ErrorCode.BAD_REQUEST, "Subtask score must not be negative");
            }
            validated.add(new ValidatedSubtask(key, title, score, subtask.sortOrder() == null ? i : subtask.sortOrder()));
        }
        return validated;
    }

    private String normalizeOptionalSubtaskKey(String subtaskKey) {
        if (!StringUtils.hasText(subtaskKey)) {
            return null;
        }
        String normalized = subtaskKey.trim();
        if (normalized.length() > 64) {
            throw new DomainException(ErrorCode.BAD_REQUEST, "Testcase subtask key is too long");
        }
        return normalized;
    }

    private ZipEntryInfo requireFile(Map<String, ZipEntryInfo> entries, String path, String role) {
        ZipEntryInfo entry = entries.get(path);
        if (entry == null) {
            throw new DomainException(ErrorCode.BAD_REQUEST, "Testcase " + role + " file is missing: " + path);
        }
        if (entry.directory()) {
            throw new DomainException(ErrorCode.BAD_REQUEST, "Testcase " + role + " path is a directory: " + path);
        }
        return entry;
    }

    private String normalizeZipPath(String rawPath) {
        if (!StringUtils.hasText(rawPath)) {
            throw new DomainException(ErrorCode.BAD_REQUEST, "Testcase zip path is required");
        }
        String path = rawPath.trim();
        if (path.contains("\\") || path.startsWith("/") || path.startsWith("\\") || path.matches("^[A-Za-z]:.*")) {
            throw new DomainException(ErrorCode.BAD_REQUEST, "Unsafe testcase zip path: " + rawPath);
        }
        while (path.endsWith("/")) {
            path = path.substring(0, path.length() - 1);
        }
        String[] segments = path.split("/");
        List<String> normalized = new ArrayList<>();
        for (String segment : segments) {
            if (!StringUtils.hasText(segment) || ".".equals(segment) || "..".equals(segment) || segment.contains(":")) {
                throw new DomainException(ErrorCode.BAD_REQUEST, "Unsafe testcase zip path: " + rawPath);
            }
            normalized.add(segment);
        }
        return String.join("/", normalized);
    }

    private void rejectSymlinksBestEffort(Path zipPath) {
        try (FileSystem fileSystem = FileSystems.newFileSystem(zipPath, Map.of())) {
            for (Path root : fileSystem.getRootDirectories()) {
                try (var paths = Files.walk(root)) {
                    paths.forEach(path -> {
                        if (Files.isSymbolicLink(path)) {
                            throw new DomainException(ErrorCode.BAD_REQUEST, "Testcase package must not contain symbolic links");
                        }
                    });
                }
            }
        } catch (DomainException ex) {
            throw ex;
        } catch (IOException | RuntimeException ignored) {
            // The ZipFile scan below remains the authoritative validation path.
        }
    }

    public record ManifestPayload(String version, ManifestChecker checker, List<ManifestSubtask> subtasks,
                                  List<ManifestCase> cases) {
    }

    public record ManifestChecker(String type, String language, String source, String protocol) {
    }

    public record ManifestSubtask(String key, String title, BigDecimal score, Integer sortOrder) {
    }

    public record ManifestCase(String name, String input, String output, Boolean sample, String subtaskKey,
                               Integer score) {
    }

    private record ZipEntryInfo(String path, boolean directory, long sizeBytes) {
    }

    private record ZipScan(Map<String, ZipEntryInfo> entries, byte[] manifestBytes) {
    }

    public record ValidatedPackage(String version, String manifestJson, ValidatedChecker checker,
                                   List<ValidatedSubtask> subtasks, List<ValidatedCase> cases) {
    }

    public record ValidatedChecker(TestcaseCheckerType type, String language, String source,
                                   TestcaseCheckerProtocol protocol) {
    }

    public record ValidatedSubtask(String key, String title, BigDecimal score, int sortOrder) {
    }

    public record ValidatedCase(String name, String inputPath, String outputPath, boolean sample, String subtaskKey,
                                Integer score, long inputSizeBytes, long outputSizeBytes, int sortOrder) {
    }
}
