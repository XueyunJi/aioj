package com.aioj.next.ai.domain;

import com.aioj.next.common.error.DomainException;
import com.aioj.next.common.error.ErrorCode;
import com.aioj.next.contract.ai.AccountImportParseRequest;
import com.aioj.next.contract.ai.AccountImportParseResponse;
import com.aioj.next.contract.ai.AccountImportParsedUser;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Service
public class AccountImportParseService {
    private static final int MAX_USERS = 200;

    private final ObjectMapper objectMapper;
    private final AiModelConfigResolver configResolver;
    private final AiModelCompletionClient completionClient;
    private final AiQuotaService aiQuotaService;
    private final AiCapacityService capacityService;

    public AccountImportParseService(
            ObjectMapper objectMapper,
            AiModelConfigResolver configResolver,
            AiModelCompletionClient completionClient,
            AiQuotaService aiQuotaService,
            AiCapacityService capacityService
    ) {
        this.objectMapper = objectMapper;
        this.configResolver = configResolver;
        this.completionClient = completionClient;
        this.aiQuotaService = aiQuotaService;
        this.capacityService = capacityService;
    }

    public AccountImportParseResponse parse(Long userId, AccountImportParseRequest request) {
        String text = request == null ? null : request.text();
        if (text == null || text.isBlank()) {
            throw new DomainException(ErrorCode.BAD_REQUEST, "Account import text is required");
        }
        String normalizedText = text.trim();
        List<AccountImportParsedUser> local = localParse(normalizedText);
        return capacityService.call(AiCapacityService.AiWorkload.INTENT_MEMORY,
                () -> parseWithModelOrFallback(userId, normalizedText, local));
    }

    private AccountImportParseResponse parseWithModelOrFallback(Long userId, String text,
                                                                List<AccountImportParsedUser> localFallback) {
        AiModelEffectiveConfig config = configResolver.effectiveConfig(AiModelScope.ACCOUNT_IMPORT_PARSE);
        if (!config.enabled() || !config.hasApiKey()) {
            return new AccountImportParseResponse(localFallback, "AI 账号解析模型未配置，已使用本地规则解析。");
        }
        try {
            aiQuotaService.assertMonthlyAvailable(userId);
            AiModelCompletionClient.CompletionResult result = completionClient.complete(
                    config,
                    List.of(
                            message("system", """
                                    你是账号导入解析器。只输出 JSON 对象，不要解释。
                                    从花名册、学号列表、CSV/TSV 或符号分隔文本中提取账号候选。
                                    输出格式：
                                    {"users":[{"account":"string","displayName":"string","email":"string","confidence":0.0,"sourceLine":"string"}],"note":"string"}
                                    账号通常是学号、工号或登录名；displayName 是姓名或展示名；email 可为空字符串。
                                    中文姓名必须原样保留。遇到全角＠、全角句点等常见录入符号时先规范化。
                                    如果原文中的邮箱字段疑似邮箱但格式不完整，保留原字段给预览校验标注，不要编造新邮箱。
                                    最多返回 200 人；不要编造原文不存在的信息。
                                    """),
                            message("user", text)
                    ),
                    config.temperatureOr(0.1),
                    config.maxTokensOr(1800),
                    true
            );
            AccountImportParseResponse parsed = parseJson(stripMarkdownFence(result.content()));
            aiQuotaService.record(userId, result.provider(), result.model(), result.promptTokens(),
                    result.completionTokens(), true);
            if (parsed.users().isEmpty()) {
                return new AccountImportParseResponse(localFallback, "AI 未解析到有效账号，已使用本地规则解析。");
            }
            return parsed;
        } catch (RuntimeException ex) {
            aiQuotaService.record(userId, config.provider(), config.model(), 0, 0, false);
            return new AccountImportParseResponse(localFallback, "AI 解析失败，已使用本地规则解析。");
        }
    }

    private AccountImportParseResponse parseJson(String content) {
        try {
            JsonNode root = objectMapper.readTree(content);
            JsonNode usersNode = root.path("users");
            if (!usersNode.isArray()) {
                usersNode = root.path("candidates");
            }
            List<AccountImportParsedUser> users = new ArrayList<>();
            if (usersNode.isArray()) {
                for (JsonNode item : usersNode) {
                    if (users.size() >= MAX_USERS) {
                        break;
                    }
                    AccountImportParsedUser user = parsedUser(item);
                    if (user != null) {
                        users.add(user);
                    }
                }
            }
            String note = trimTo(root.path("note").asText("AI 已解析账号候选。"), 300);
            return new AccountImportParseResponse(users, note);
        } catch (Exception ex) {
            throw new DomainException(ErrorCode.INTERNAL_ERROR, "AI account import response could not be parsed");
        }
    }

    private AccountImportParsedUser parsedUser(JsonNode item) {
        String account = cleanToken(item.path("account").asText(""));
        if (account == null) {
            return null;
        }
        String displayName = cleanText(item.path("displayName").asText(""));
        if (displayName == null) {
            displayName = cleanText(item.path("name").asText(""));
        }
        if (displayName == null) {
            displayName = account;
        }
        String email = cleanEmail(item.path("email").asText(""));
        double confidence = Math.max(0, Math.min(1, item.path("confidence").asDouble(0.8)));
        return new AccountImportParsedUser(account, displayName, email, confidence,
                trimTo(item.path("sourceLine").asText(""), 300));
    }

    private List<AccountImportParsedUser> localParse(String text) {
        Map<String, AccountImportParsedUser> users = new LinkedHashMap<>();
        String[] lines = text.replace("\r\n", "\n").replace('\r', '\n').split("\n");
        for (String rawLine : lines) {
            if (users.size() >= MAX_USERS) {
                break;
            }
            String line = rawLine.trim();
            if (line.isBlank() || looksLikeHeader(line)) {
                continue;
            }
            String[] parts = line.split("[,，\\t;；|]+");
            if (parts.length == 1) {
                parts = line.split("\\s+");
            }
            AccountImportParsedUser user = localUser(parts, line);
            if (user != null) {
                users.putIfAbsent(user.account(), user);
            }
        }
        return List.copyOf(users.values());
    }

    private AccountImportParsedUser localUser(String[] parts, String sourceLine) {
        String email = null;
        String account = null;
        String displayName = null;
        for (String part : parts) {
            String value = cleanText(part);
            if (value == null) {
                continue;
            }
            String emailCandidate = cleanEmail(value);
            if (email == null && emailCandidate != null) {
                email = emailCandidate;
                continue;
            }
            if (account == null) {
                account = cleanToken(value);
                if (account != null) {
                    continue;
                }
            }
            if (displayName == null) {
                displayName = value;
            }
        }
        if (account == null) {
            return null;
        }
        if (displayName == null) {
            displayName = account;
        }
        return new AccountImportParsedUser(account, displayName, email, 0.55, trimTo(sourceLine, 300));
    }

    private boolean looksLikeHeader(String line) {
        String normalized = line.toLowerCase(Locale.ROOT);
        return normalized.contains("account") || normalized.contains("username")
                || normalized.contains("学号") || normalized.contains("账号")
                || normalized.contains("姓名") || normalized.contains("邮箱");
    }

    private String cleanToken(String value) {
        String text = cleanText(value);
        if (text == null || text.contains("@")) {
            return null;
        }
        String cleaned = text.replaceAll("\\s+", "");
        if (cleaned.isBlank() || cleaned.length() > 64) {
            return null;
        }
        return cleaned;
    }

    private String cleanEmail(String value) {
        String text = cleanText(value);
        if (text == null) {
            return null;
        }
        text = text.replace('＠', '@')
                .replace('．', '.')
                .replace('。', '.')
                .replaceAll("\\s+", "");
        if (!text.contains("@") || text.length() > 160) {
            return null;
        }
        return text;
    }

    private String cleanText(String value) {
        if (value == null) {
            return null;
        }
        String text = value.replace('\uFEFF', ' ').trim();
        return text.isBlank() ? null : text;
    }

    private String trimTo(String value, int maxLength) {
        String text = value == null ? "" : value.trim();
        return text.length() <= maxLength ? text : text.substring(0, maxLength);
    }

    private String stripMarkdownFence(String value) {
        String content = value == null ? "" : value.trim();
        if (content.startsWith("```")) {
            int firstBreak = content.indexOf('\n');
            int lastFence = content.lastIndexOf("```");
            if (firstBreak >= 0 && lastFence > firstBreak) {
                return content.substring(firstBreak + 1, lastFence).trim();
            }
        }
        return content;
    }

    private Map<String, String> message(String role, String content) {
        return Map.of("role", role, "content", content);
    }
}
