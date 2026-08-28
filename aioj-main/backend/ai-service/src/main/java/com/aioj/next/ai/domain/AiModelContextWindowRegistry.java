package com.aioj.next.ai.domain;

import com.aioj.next.ai.config.AiProperties;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class AiModelContextWindowRegistry {
    private static final Pattern SUFFIX_WINDOW = Pattern.compile("(^|[-_])([0-9]{1,3})k($|[-_])");
    private static final Map<String, Integer> OFFICIAL_WINDOWS = officialWindows();

    private final AiProperties properties;

    public AiModelContextWindowRegistry(AiProperties properties) {
        this.properties = properties;
    }

    public int resolve(String model) {
        String normalized = normalize(model);
        if (!normalized.isBlank()) {
            Integer exact = OFFICIAL_WINDOWS.get(normalized);
            if (exact != null) {
                return exact;
            }
            for (Map.Entry<String, Integer> entry : OFFICIAL_WINDOWS.entrySet()) {
                if (normalized.contains(entry.getKey())) {
                    return entry.getValue();
                }
            }
            Matcher matcher = SUFFIX_WINDOW.matcher(normalized);
            if (matcher.find()) {
                return Integer.parseInt(matcher.group(2)) * 1000;
            }
        }
        return Math.max(1, properties.getContext().getDefaultWindowTokens());
    }

    private static Map<String, Integer> officialWindows() {
        Map<String, Integer> windows = new LinkedHashMap<>();
        windows.put("deepseek-v4-flash", 1_000_000);
        windows.put("deepseek-v4-pro", 1_000_000);
        windows.put("deepseek-chat", 1_000_000);
        windows.put("deepseek-reasoner", 1_000_000);
        windows.put("kimi-k2.7-code-highspeed", 256_000);
        windows.put("kimi-k2.7-code", 256_000);
        windows.put("kimi-k2.6", 256_000);
        windows.put("kimi-k2.5", 256_000);
        windows.put("moonshot-v1-8k-vision-preview", 8_000);
        windows.put("moonshot-v1-32k-vision-preview", 32_000);
        windows.put("moonshot-v1-128k-vision-preview", 128_000);
        windows.put("moonshot-v1-8k", 8_000);
        windows.put("moonshot-v1-32k", 32_000);
        windows.put("moonshot-v1-128k", 128_000);
        return Collections.unmodifiableMap(windows);
    }

    private String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }
}
