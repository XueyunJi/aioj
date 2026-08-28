package com.aioj.next.ai.domain;

import com.aioj.next.ai.config.AiProperties;
import com.aioj.next.ai.persistence.entity.AiRetrievalChunkEntity;
import com.aioj.next.ai.persistence.mapper.AiRetrievalChunkMapper;
import com.aioj.next.common.error.DomainException;
import com.aioj.next.common.error.ErrorCode;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

@Service
public class AiRetrievalService {
    private static final Logger log = LoggerFactory.getLogger(AiRetrievalService.class);

    public static final String SENSITIVITY_USER_PRIVATE_SAFE = "USER_PRIVATE_SAFE";
    public static final String SENSITIVITY_NO_PERSIST_CODE = "NO_PERSIST_CODE";
    public static final String SENSITIVITY_SOURCE_CODE = "SOURCE_CODE";
    public static final String SENSITIVITY_RAW_OUTPUT = "RAW_OUTPUT";

    private static final int MAX_CHUNK_LENGTH = 3000;
    private static final int MAX_METADATA_STRING_LENGTH = 300;

    private final AiRetrievalChunkMapper chunkMapper;
    private final AiProvider aiProvider;
    private final AiCapacityService aiCapacityService;
    private final AiProperties properties;
    private final ObjectMapper objectMapper;

    public AiRetrievalService(
            AiRetrievalChunkMapper chunkMapper,
            AiProvider aiProvider,
            AiCapacityService aiCapacityService,
            AiProperties properties,
            ObjectMapper objectMapper
    ) {
        this.chunkMapper = chunkMapper;
        this.aiProvider = aiProvider;
        this.aiCapacityService = aiCapacityService;
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public void indexChunk(Long userId, String ownerType, String ownerId, String text) {
        indexChunk(userId, ownerType, ownerId, text, AiRetrievalChunkMetadata.safe());
    }

    @Transactional
    public void indexChunk(Long userId, String ownerType, String ownerId, String text, AiRetrievalChunkMetadata metadata) {
        AiRetrievalChunkMetadata safeMetadata = metadata == null ? AiRetrievalChunkMetadata.safe() : metadata.safeCopy();
        if (userId == null || ownerType == null || ownerId == null || isDisallowedSensitivity(safeMetadata.sensitivity())) {
            return;
        }
        String normalized = sanitizeForRetrieval(text);
        if (normalized.isBlank() || !hasUsefulContent(normalized)) {
            return;
        }
        String hash = sha256(normalized);
        AiRetrievalChunkEntity existing = chunkMapper.selectOne(new QueryWrapper<AiRetrievalChunkEntity>()
                .eq("owner_type", ownerType)
                .eq("owner_id", ownerId)
                .eq("text_hash", hash)
                .last("LIMIT 1"));
        if (existing != null) {
            return;
        }

        AiRetrievalChunkEntity chunk = new AiRetrievalChunkEntity();
        LocalDateTime now = LocalDateTime.now();
        chunk.setUserId(userId);
        chunk.setOwnerType(ownerType);
        chunk.setOwnerId(ownerId);
        chunk.setChunkText(normalized);
        chunk.setMetadataJson(toJson(sanitizeAttributes(safeMetadata.attributes())));
        chunk.setSensitivity(nonBlank(safeMetadata.sensitivity(), SENSITIVITY_USER_PRIVATE_SAFE));
        chunk.setProblemId(safeMetadata.problemId());
        chunk.setSubmissionId(safeMetadata.submissionId());
        chunk.setContestId(safeMetadata.contestId());
        chunk.setContestRunId(safeMetadata.contestRunId());
        chunk.setContestProblemId(safeMetadata.contestProblemId());
        chunk.setAlgorithmKey(normalizeKey(safeMetadata.algorithmKey()));
        chunk.setProfileKey(normalizeKey(safeMetadata.profileKey()));
        chunk.setTextHash(hash);
        chunk.setCreatedAt(now);
        chunk.setUpdatedAt(now);

        Optional<List<Double>> embedding = embedWithCapacity(normalized);
        embedding.ifPresent(values -> {
            try {
                chunk.setEmbeddingJson(objectMapper.writeValueAsString(values));
                chunk.setEmbeddingModel(properties.getEmbedding().getModel());
                chunk.setEmbeddingDimension(values.size());
            } catch (Exception ignored) {
                chunk.setEmbeddingJson(null);
            }
        });

        chunkMapper.insert(chunk);
    }

    @Transactional
    public void deleteOwner(Long userId, String ownerType, String ownerId) {
        if (userId == null || ownerType == null || ownerId == null) {
            return;
        }
        chunkMapper.delete(new QueryWrapper<AiRetrievalChunkEntity>()
                .eq("user_id", userId)
                .eq("owner_type", ownerType)
                .eq("owner_id", ownerId));
    }

    public List<String> search(Long userId, String query, List<String> ownerTypes, int limit) {
        return searchDetailed(userId, query, ownerTypes, limit, AiRetrievalSearchContext.empty()).stream()
                .map(AiRetrievalHit::content)
                .toList();
    }

    public List<AiRetrievalHit> searchDetailed(
            Long userId,
            String query,
            List<String> ownerTypes,
            int limit,
            AiRetrievalSearchContext context
    ) {
        String normalizedQuery = normalize(query);
        if (userId == null || normalizedQuery.isBlank()) {
            return List.of();
        }
        QueryWrapper<AiRetrievalChunkEntity> wrapper = new QueryWrapper<AiRetrievalChunkEntity>()
                .eq("user_id", userId)
                .orderByDesc("updated_at")
                .last("LIMIT 200");
        if (ownerTypes != null && !ownerTypes.isEmpty()) {
            wrapper.in("owner_type", ownerTypes);
        }
        List<AiRetrievalChunkEntity> candidates = chunkMapper.selectList(wrapper);
        if (candidates.isEmpty()) {
            return List.of();
        }
        AiRetrievalSearchContext searchContext = context == null ? AiRetrievalSearchContext.empty() : context.normalized();
        Optional<List<Double>> queryVector = embedWithCapacity(normalizedQuery);
        return candidates.stream()
                .filter(this::isRenderable)
                .filter(chunk -> !"memory".equals(chunk.getOwnerType()) || AiMemoryService.isRecallableMemoryChunk(chunk.getChunkText()))
                .map(chunk -> score(normalizedQuery, queryVector, chunk, searchContext))
                .filter(item -> item.score() > 0)
                .sorted(Comparator.comparingDouble(ScoredChunk::score).reversed())
                .limit(Math.max(1, limit))
                .map(ScoredChunk::toHit)
                .toList();
    }

    private boolean isRenderable(AiRetrievalChunkEntity chunk) {
        if (chunk == null || chunk.getChunkText() == null || chunk.getChunkText().isBlank()) {
            return false;
        }
        if (isDisallowedSensitivity(chunk.getSensitivity())) {
            return false;
        }
        String sanitized = sanitizeForRetrieval(chunk.getChunkText());
        return !sanitized.isBlank() && hasUsefulContent(sanitized);
    }

    private ScoredChunk score(
            String query,
            Optional<List<Double>> queryVector,
            AiRetrievalChunkEntity chunk,
            AiRetrievalSearchContext context
    ) {
        List<String> reasons = new ArrayList<>();
        double score = semanticOrKeywordScore(query, queryVector, chunk, reasons);
        if (context.submissionId() != null && context.submissionId().equals(chunk.getSubmissionId())) {
            score += 2.0;
            reasons.add("same_submission");
        }
        if (context.problemId() != null && context.problemId().equals(chunk.getProblemId())) {
            score += 1.2;
            reasons.add("same_problem");
        }
        if (matchesAny(context.algorithmKeys(), chunk.getAlgorithmKey())) {
            score += 0.8;
            reasons.add("algorithm_key_match");
        }
        if (matchesAny(context.profileKeys(), chunk.getProfileKey())) {
            score += 0.8;
            reasons.add("profile_key_match");
        }
        double sourceBoost = sourceBoost(chunk.getOwnerType());
        if (sourceBoost > 0) {
            score += sourceBoost;
            reasons.add("source_priority:" + chunk.getOwnerType());
        }
        double recencyBoost = recencyBoost(chunk.getUpdatedAt());
        if (recencyBoost > 0) {
            score += recencyBoost;
            reasons.add("recent");
        }
        return new ScoredChunk(chunk, sanitizeForRetrieval(chunk.getChunkText()), round(score), reasons, readMetadata(chunk));
    }

    private double semanticOrKeywordScore(
            String query,
            Optional<List<Double>> queryVector,
            AiRetrievalChunkEntity chunk,
            List<String> reasons
    ) {
        if (queryVector.isPresent() && chunk.getEmbeddingJson() != null && !chunk.getEmbeddingJson().isBlank()) {
            try {
                List<Double> chunkVector = objectMapper.readValue(chunk.getEmbeddingJson(), new TypeReference<>() {
                });
                double cosine = cosine(queryVector.get(), chunkVector);
                if (cosine > 0) {
                    reasons.add("semantic_match");
                    return cosine + 0.2;
                }
            } catch (Exception ignored) {
                // Fall through to keyword scoring.
            }
        }
        String text = chunk.getChunkText() == null ? "" : chunk.getChunkText().toLowerCase(Locale.ROOT);
        String[] words = query.toLowerCase(Locale.ROOT).split("\\s+");
        double score = 0;
        int matched = 0;
        for (String word : words) {
            String normalized = normalizeKey(word);
            if (normalized.length() >= 2 && text.contains(normalized)) {
                score += 0.1;
                matched++;
            }
        }
        if (matched > 0) {
            reasons.add("keyword_match:" + matched);
        }
        return score;
    }

    private double sourceBoost(String ownerType) {
        return switch (ownerType == null ? "" : ownerType) {
            case "submission_analysis" -> 0.55;
            case "profile_evidence" -> 0.45;
            case "learning_profile" -> 0.35;
            case "conversation_summary" -> 0.25;
            case "memory" -> 0.20;
            case "message" -> 0.10;
            case "code_snapshot" -> 0.05;
            default -> 0;
        };
    }

    private double recencyBoost(LocalDateTime updatedAt) {
        if (updatedAt == null) {
            return 0;
        }
        long days = Math.max(0, Duration.between(updatedAt, LocalDateTime.now()).toDays());
        if (days <= 1) {
            return 0.20;
        }
        if (days <= 7) {
            return 0.12;
        }
        if (days <= 30) {
            return 0.05;
        }
        return 0;
    }

    private boolean matchesAny(Set<String> values, String candidate) {
        return candidate != null && values != null && values.contains(normalizeKey(candidate));
    }

    private Optional<List<Double>> embedWithCapacity(String input) {
        try {
            return aiCapacityService.call(
                    AiCapacityService.AiWorkload.INTENT_MEMORY,
                    () -> aiProvider.embed(input)
            );
        } catch (DomainException ex) {
            if (ex.errorCode() == ErrorCode.TOO_MANY_REQUESTS) {
                AiFailureMetrics.incrementEmbeddingCapacityRejection();
                log.warn("embedding capacity rejected; retrieval degrades to keyword scoring for this call");
                return Optional.empty();
            }
            throw ex;
        }
    }

    private double cosine(List<Double> left, List<Double> right) {
        int size = Math.min(left.size(), right.size());
        if (size == 0) {
            return 0;
        }
        double dot = 0;
        double leftNorm = 0;
        double rightNorm = 0;
        for (int i = 0; i < size; i++) {
            double l = left.get(i);
            double r = right.get(i);
            dot += l * r;
            leftNorm += l * l;
            rightNorm += r * r;
        }
        if (leftNorm == 0 || rightNorm == 0) {
            return 0;
        }
        return dot / (Math.sqrt(leftNorm) * Math.sqrt(rightNorm));
    }

    private String sanitizeForRetrieval(String value) {
        String normalized = normalize(value)
                .replaceAll("(?i)\"(codeText|stdoutExcerpt|stderrExcerpt)\"\\s*:\\s*\"(?:\\\\.|[^\"\\\\])*\"", "\"$1\":\"[omitted]\"")
                .replaceAll("(?i)(codeText|stdoutExcerpt|stderrExcerpt)\\s*[:=].*", "$1=[omitted]")
                .replaceAll("(?i)(token|secret|password|api[_-]?key)\\s*[:=]\\s*\\S+", "$1=***")
                .replaceAll("(?i)sk-[a-z0-9_\\-]{4,}", "sk-***")
                .replaceAll("(?i)(bearer)\\s+[a-z0-9._\\-]{8,}", "$1 ***");
        if (normalized.isBlank()) {
            return "";
        }
        StringBuilder kept = new StringBuilder();
        boolean inCodeFence = false;
        boolean inRawOutputBlock = false;
        boolean omittedCodeRun = false;
        for (String rawLine : normalized.split("\\R", -1)) {
            String line = rawLine.trim();
            if (line.startsWith("```")) {
                inCodeFence = !inCodeFence;
                if (!omittedCodeRun) {
                    appendLine(kept, "[code block omitted from retrieval]");
                    omittedCodeRun = true;
                }
                continue;
            }
            if (isRawOutputLabel(line)) {
                inRawOutputBlock = true;
                appendLine(kept, "[raw output omitted from retrieval]");
                continue;
            }
            if (inRawOutputBlock) {
                if (isContextBoundary(line) || isKnownContextKey(line)) {
                    inRawOutputBlock = false;
                } else {
                    continue;
                }
            }
            if (inCodeFence || looksLikeCodeLine(line)) {
                if (!omittedCodeRun) {
                    appendLine(kept, "[code line omitted from retrieval]");
                    omittedCodeRun = true;
                }
                continue;
            }
            omittedCodeRun = false;
            appendLine(kept, rawLine);
        }
        String safe = kept.toString().replaceAll("\\n{3,}", "\n\n").trim();
        return safe.length() <= MAX_CHUNK_LENGTH ? safe : safe.substring(0, MAX_CHUNK_LENGTH);
    }

    private boolean hasUsefulContent(String value) {
        String useful = value == null ? "" : value
                .replace("[code block omitted from retrieval]", "")
                .replace("[code line omitted from retrieval]", "")
                .replace("[raw output omitted from retrieval]", "")
                .replaceAll("\\s+", " ")
                .trim();
        return useful.length() >= 6;
    }

    private boolean isDisallowedSensitivity(String sensitivity) {
        String normalized = sensitivity == null ? SENSITIVITY_USER_PRIVATE_SAFE : sensitivity.trim().toUpperCase(Locale.ROOT);
        return SENSITIVITY_NO_PERSIST_CODE.equals(normalized)
                || SENSITIVITY_SOURCE_CODE.equals(normalized)
                || SENSITIVITY_RAW_OUTPUT.equals(normalized);
    }

    private boolean isRawOutputLabel(String line) {
        if (line == null) {
            return false;
        }
        String lower = line.toLowerCase(Locale.ROOT);
        return lower.startsWith("stdoutexcerpt:")
                || lower.startsWith("stderrexcerpt:")
                || lower.startsWith("stdout:")
                || lower.startsWith("stderr:");
    }

    private boolean isContextBoundary(String line) {
        return line != null && (line.startsWith("[") || line.startsWith("<"));
    }

    private boolean isKnownContextKey(String line) {
        if (line == null || !line.contains(":")) {
            return false;
        }
        String key = line.substring(0, line.indexOf(':')).trim();
        return List.of(
                "submissionId",
                "problemId",
                "scope",
                "contestActive",
                "language",
                "status",
                "judgeMessage",
                "runtimeMs",
                "memoryKb",
                "score",
                "maxScore",
                "codeHash",
                "codeAllowedToModel",
                "policyMessage",
                "caseResults"
        ).contains(key);
    }

    private boolean looksLikeCodeLine(String line) {
        if (line == null || line.isBlank()) {
            return false;
        }
        String lower = line.toLowerCase(Locale.ROOT);
        return lower.startsWith("#include")
                || lower.startsWith("using namespace")
                || lower.startsWith("public class")
                || lower.startsWith("public static void main")
                || lower.startsWith("class solution")
                || lower.startsWith("def main")
                || lower.startsWith("import sys")
                || lower.startsWith("from sys")
                || lower.startsWith("if __name__")
                || lower.contains("cin >>")
                || lower.contains("cout <<")
                || lower.contains("sys.stdin")
                || lower.contains("int main(")
                || lower.matches(".*[{};]\\s*$");
    }

    private void appendLine(StringBuilder kept, String line) {
        if (line == null || line.isBlank()) {
            return;
        }
        if (!kept.isEmpty()) {
            kept.append('\n');
        }
        kept.append(line.trim());
    }

    private String normalize(String value) {
        if (value == null) {
            return "";
        }
        String normalized = value.replace("\r\n", "\n").replace('\r', '\n').trim();
        return normalized.length() <= MAX_CHUNK_LENGTH ? normalized : normalized.substring(0, MAX_CHUNK_LENGTH);
    }

    private String normalizeKey(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT).replaceAll("\\s+", "_");
    }

    private String nonBlank(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private String toJson(Map<String, Object> value) {
        if (value == null || value.isEmpty()) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception ex) {
            return null;
        }
    }

    private Map<String, Object> readMetadata(AiRetrievalChunkEntity chunk) {
        if (chunk == null || chunk.getMetadataJson() == null || chunk.getMetadataJson().isBlank()) {
            return Map.of();
        }
        try {
            Map<String, Object> metadata = objectMapper.readValue(chunk.getMetadataJson(), new TypeReference<>() {
            });
            return sanitizeAttributes(metadata);
        } catch (Exception ex) {
            return Map.of();
        }
    }

    private Map<String, Object> sanitizeAttributes(Map<String, Object> source) {
        if (source == null || source.isEmpty()) {
            return Map.of();
        }
        Map<String, Object> sanitized = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : source.entrySet()) {
            String key = entry.getKey();
            if (key == null || key.isBlank()) {
                continue;
            }
            sanitized.put(key, sanitizeAttributeValue(key, entry.getValue()));
        }
        return sanitized;
    }

    @SuppressWarnings("unchecked")
    private Object sanitizeAttributeValue(String key, Object value) {
        String normalizedKey = normalizeKey(key).replace("_", "");
        if (normalizedKey.equals("code")
                || normalizedKey.equals("codetext")
                || normalizedKey.equals("sourcecode")
                || normalizedKey.equals("stdoutexcerpt")
                || normalizedKey.equals("stderrexcerpt")) {
            return "[omitted]";
        }
        if (value instanceof String text) {
            String safe = sanitizeForRetrieval(text);
            return safe.length() <= MAX_METADATA_STRING_LENGTH ? safe : safe.substring(0, MAX_METADATA_STRING_LENGTH);
        }
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> nested = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                nested.put(String.valueOf(entry.getKey()), sanitizeAttributeValue(String.valueOf(entry.getKey()), entry.getValue()));
            }
            return nested;
        }
        if (value instanceof List<?> list) {
            return list.stream().map(item -> sanitizeAttributeValue(key, item)).toList();
        }
        return value;
    }

    private double round(double value) {
        return Math.round(value * 1000.0) / 1000.0;
    }

    private String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception ex) {
            return Integer.toHexString(value.hashCode());
        }
    }

    public record AiRetrievalChunkMetadata(
            Long problemId,
            Long submissionId,
            Long contestId,
            Long contestRunId,
            Long contestProblemId,
            String algorithmKey,
            String profileKey,
            String sensitivity,
            Map<String, Object> attributes
    ) {
        public static AiRetrievalChunkMetadata safe() {
            return new AiRetrievalChunkMetadata(null, null, null, null, null, null, null, SENSITIVITY_USER_PRIVATE_SAFE, Map.of());
        }

        public static AiRetrievalChunkMetadata of(
                Long problemId,
                Long submissionId,
                Long contestId,
                Long contestRunId,
                Long contestProblemId,
                String algorithmKey,
                String profileKey,
                Map<String, Object> attributes
        ) {
            return new AiRetrievalChunkMetadata(problemId, submissionId, contestId, contestRunId, contestProblemId,
                    algorithmKey, profileKey, SENSITIVITY_USER_PRIVATE_SAFE, attributes);
        }

        private AiRetrievalChunkMetadata safeCopy() {
            Map<String, Object> safeAttributes = new LinkedHashMap<>();
            if (attributes != null) {
                for (Map.Entry<String, Object> entry : attributes.entrySet()) {
                    if (entry.getKey() != null && entry.getValue() != null) {
                        safeAttributes.put(entry.getKey(), entry.getValue());
                    }
                }
            }
            return new AiRetrievalChunkMetadata(
                    problemId,
                    submissionId,
                    contestId,
                    contestRunId,
                    contestProblemId,
                    algorithmKey,
                    profileKey,
                    sensitivity == null || sensitivity.isBlank() ? SENSITIVITY_USER_PRIVATE_SAFE : sensitivity.trim().toUpperCase(Locale.ROOT),
                    safeAttributes.isEmpty() ? Map.of() : safeAttributes
            );
        }
    }

    public record AiRetrievalSearchContext(
            Long problemId,
            Long submissionId,
            Set<String> algorithmKeys,
            Set<String> profileKeys
    ) {
        public static AiRetrievalSearchContext empty() {
            return new AiRetrievalSearchContext(null, null, Set.of(), Set.of());
        }

        private AiRetrievalSearchContext normalized() {
            return new AiRetrievalSearchContext(
                    problemId,
                    submissionId,
                    normalizeSet(algorithmKeys),
                    normalizeSet(profileKeys)
            );
        }

        private static Set<String> normalizeSet(Set<String> values) {
            if (values == null || values.isEmpty()) {
                return Set.of();
            }
            Set<String> normalized = new HashSet<>();
            for (String value : values) {
                if (value != null && !value.isBlank()) {
                    normalized.add(value.trim().toLowerCase(Locale.ROOT).replaceAll("\\s+", "_"));
                }
            }
            return normalized;
        }
    }

    public record AiRetrievalHit(
            String ownerType,
            String ownerId,
            String content,
            double score,
            List<String> reasons,
            Long problemId,
            Long submissionId,
            Long contestId,
            Long contestRunId,
            Long contestProblemId,
            String algorithmKey,
            String profileKey,
            String sensitivity,
            Map<String, Object> metadata
    ) {
        public static AiRetrievalHit legacy(String content) {
            return new AiRetrievalHit("legacy", "", content, 0, List.of("legacy_search"), null, null,
                    null, null, null, null, null, SENSITIVITY_USER_PRIVATE_SAFE, Map.of());
        }

        public Map<String, Object> debugMetadata() {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("ownerType", ownerType);
            map.put("ownerId", ownerId);
            map.put("score", score);
            map.put("reasons", reasons == null ? List.of() : reasons);
            if (problemId != null) {
                map.put("problemId", String.valueOf(problemId));
            }
            if (submissionId != null) {
                map.put("submissionId", String.valueOf(submissionId));
            }
            if (contestId != null) {
                map.put("contestId", String.valueOf(contestId));
            }
            if (contestRunId != null) {
                map.put("contestRunId", String.valueOf(contestRunId));
            }
            if (contestProblemId != null) {
                map.put("contestProblemId", String.valueOf(contestProblemId));
            }
            if (algorithmKey != null && !algorithmKey.isBlank()) {
                map.put("algorithmKey", algorithmKey);
            }
            if (profileKey != null && !profileKey.isBlank()) {
                map.put("profileKey", profileKey);
            }
            map.put("sensitivity", sensitivity == null ? SENSITIVITY_USER_PRIVATE_SAFE : sensitivity);
            map.put("preview", content == null || content.length() <= 240 ? content : content.substring(0, 240));
            if (metadata != null && !metadata.isEmpty()) {
                map.put("metadata", metadata);
            }
            return map;
        }
    }

    private record ScoredChunk(
            AiRetrievalChunkEntity chunk,
            String content,
            double score,
            List<String> reasons,
            Map<String, Object> metadata
    ) {
        private AiRetrievalHit toHit() {
            return new AiRetrievalHit(
                    chunk.getOwnerType(),
                    chunk.getOwnerId(),
                    content,
                    score,
                    reasons,
                    chunk.getProblemId(),
                    chunk.getSubmissionId(),
                    chunk.getContestId(),
                    chunk.getContestRunId(),
                    chunk.getContestProblemId(),
                    chunk.getAlgorithmKey(),
                    chunk.getProfileKey(),
                    chunk.getSensitivity(),
                    metadata == null ? Map.of() : metadata
            );
        }
    }
}
