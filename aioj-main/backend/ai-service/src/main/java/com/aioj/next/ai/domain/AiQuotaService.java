package com.aioj.next.ai.domain;

import com.aioj.next.ai.config.AiProperties;
import com.aioj.next.ai.persistence.entity.AiQuotaPolicyEntity;
import com.aioj.next.ai.persistence.entity.AiUsageRecordEntity;
import com.aioj.next.ai.persistence.mapper.AiQuotaPolicyMapper;
import com.aioj.next.ai.persistence.mapper.AiUsageRecordMapper;
import com.aioj.next.common.error.DomainException;
import com.aioj.next.common.error.ErrorCode;
import com.aioj.next.contract.ai.AiChatRequest;
import com.aioj.next.contract.ai.AiUsageResponse;
import com.aioj.next.contract.ai.DailyAiUsageStatsResponse;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class AiQuotaService {
    private static final ZoneId ZONE = ZoneId.systemDefault();

    private final AiProperties properties;
    private final AiQuotaPolicyMapper quotaPolicyMapper;
    private final AiUsageRecordMapper usageRecordMapper;

    public AiQuotaService(AiProperties properties, AiQuotaPolicyMapper quotaPolicyMapper, AiUsageRecordMapper usageRecordMapper) {
        this.properties = properties;
        this.quotaPolicyMapper = quotaPolicyMapper;
        this.usageRecordMapper = usageRecordMapper;
    }

    public void assertAvailable(Long userId) {
        QuotaLimits limits = limitsFor(userId);
        if (countUsage(userId, startOfRecentWindow(limits.recentWindowHours())) >= limits.rollingLimit()) {
            throw new DomainException(ErrorCode.TOO_MANY_REQUESTS, "AI rolling quota exceeded");
        }
        assertMonthlyAvailable(userId, limits);
    }

    public void assertMonthlyAvailable(Long userId) {
        assertMonthlyAvailable(userId, limitsFor(userId));
    }

    private void assertMonthlyAvailable(Long userId, QuotaLimits limits) {
        if (countUsage(userId, startOfMonth()) >= limits.monthlyLimit()) {
            throw new DomainException(ErrorCode.TOO_MANY_REQUESTS, "AI monthly quota exceeded");
        }
    }

    @Transactional
    public void record(Long userId, String provider, String model, long promptTokens, long completionTokens, boolean success) {
        record(userId, provider, model, promptTokens, completionTokens, success, null);
    }

    @Transactional
    public void record(Long userId, String provider, String model, long promptTokens, long completionTokens, boolean success,
                       AiChatRequest.ContestContext contestContext) {
        AiUsageRecordEntity record = new AiUsageRecordEntity();
        record.setUserId(userId);
        if (contestContext != null) {
            record.setContestId(contestContext.contestId());
            record.setContestRunId(contestContext.contestRunId());
            record.setContestProblemId(contestContext.contestProblemId());
        }
        record.setProvider(blankToDefault(provider, properties.getProvider()));
        record.setModel(blankToDefault(model, properties.getModel()));
        record.setPromptTokens(Math.max(0, promptTokens));
        record.setCompletionTokens(Math.max(0, completionTokens));
        record.setSuccess(success);
        record.setCreatedAt(LocalDateTime.now());
        usageRecordMapper.insert(record);
    }

    public AiUsageResponse usage(Long userId) {
        QuotaLimits limits = limitsFor(userId);
        return new AiUsageResponse(
                countUsage(userId, startOfRecentWindow(limits.recentWindowHours())),
                limits.rollingLimit(),
                limits.recentWindowHours(),
                countUsage(userId, startOfMonth()),
                limits.monthlyLimit()
        );
    }

    public List<DailyAiUsageStatsResponse> dailyUsage(int days) {
        int safeDays = Math.min(Math.max(days, 1), 30);
        LocalDate today = LocalDate.now(ZONE);
        LocalDate startDate = today.minusDays(safeDays - 1L);
        LocalDateTime start = startDate.atStartOfDay();
        Map<String, DailyAiUsageStatsResponse> rows = dailyStats(usageRecordMapper.selectMaps(
                new QueryWrapper<AiUsageRecordEntity>()
                        .select(
                                "DATE(created_at) AS day",
                                "COUNT(*) AS calls",
                                "SUM(CASE WHEN success = 1 THEN 1 ELSE 0 END) AS successful_calls",
                                "COALESCE(SUM(prompt_tokens), 0) AS prompt_tokens",
                                "COALESCE(SUM(completion_tokens), 0) AS completion_tokens"
                        )
                        .ge("created_at", start)
                        .groupBy("DATE(created_at)")
        ));
        List<DailyAiUsageStatsResponse> result = new ArrayList<>();
        for (int i = 0; i < safeDays; i++) {
            String day = startDate.plusDays(i).toString();
            result.add(rows.getOrDefault(day, new DailyAiUsageStatsResponse(day, 0, 0, 0, 0)));
        }
        return result;
    }

    private QuotaLimits limitsFor(Long userId) {
        AiQuotaPolicyEntity userPolicy = quotaPolicyMapper.selectOne(new QueryWrapper<AiQuotaPolicyEntity>()
                .eq("enabled", true)
                .eq("scope_type", "USER")
                .eq("scope_id", userId)
                .last("LIMIT 1"));
        if (userPolicy != null) {
            return new QuotaLimits(
                    positiveOrDefault(userPolicy.getDailyLimit(), defaultRollingLimit()),
                    safeRecentWindowHours(),
                    positiveOrDefault(userPolicy.getMonthlyLimit(), properties.getMonthlyLimit()));
        }
        AiQuotaPolicyEntity globalPolicy = quotaPolicyMapper.selectOne(new QueryWrapper<AiQuotaPolicyEntity>()
                .eq("enabled", true)
                .eq("scope_type", "GLOBAL")
                .isNull("scope_id")
                .last("LIMIT 1"));
        if (globalPolicy != null) {
            return new QuotaLimits(
                    positiveOrDefault(globalPolicy.getDailyLimit(), defaultRollingLimit()),
                    safeRecentWindowHours(),
                    positiveOrDefault(globalPolicy.getMonthlyLimit(), properties.getMonthlyLimit()));
        }
        return new QuotaLimits(defaultRollingLimit(), safeRecentWindowHours(), properties.getMonthlyLimit());
    }

    private long countUsage(Long userId, LocalDateTime start) {
        return usageRecordMapper.selectCount(new QueryWrapper<AiUsageRecordEntity>()
                .eq("user_id", userId)
                .ge("created_at", start));
    }

    private LocalDateTime startOfRecentWindow(int hours) {
        return LocalDateTime.now().minusHours(Math.max(1, hours));
    }

    private LocalDateTime startOfMonth() {
        return LocalDateTime.now().toLocalDate().withDayOfMonth(1).atStartOfDay();
    }

    private String blankToDefault(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private Map<String, DailyAiUsageStatsResponse> dailyStats(List<Map<String, Object>> rows) {
        Map<String, DailyAiUsageStatsResponse> result = new LinkedHashMap<>();
        for (Map<String, Object> row : rows) {
            String day = day(row.get("day"));
            if (day == null) {
                continue;
            }
            result.put(day, new DailyAiUsageStatsResponse(
                    day,
                    number(row.get("calls")),
                    number(row.get("successful_calls")),
                    number(row.get("prompt_tokens")),
                    number(row.get("completion_tokens"))
            ));
        }
        return result;
    }

    private String day(Object value) {
        if (value == null) {
            return null;
        }
        String day = String.valueOf(value);
        return day.length() > 10 ? day.substring(0, 10) : day;
    }

    private long number(Object value) {
        return value instanceof Number number ? number.longValue() : 0L;
    }

    private int safeRecentWindowHours() {
        return Math.max(1, Math.min(properties.getRollingWindowHours(), 24));
    }

    private long defaultRollingLimit() {
        return properties.getRollingLimit() > 0 ? properties.getRollingLimit() : properties.getDailyLimit();
    }

    private long positiveOrDefault(Long value, long fallback) {
        return value != null && value > 0 ? value : fallback;
    }

    private record QuotaLimits(long rollingLimit, int recentWindowHours, long monthlyLimit) {
    }
}
