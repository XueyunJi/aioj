package com.aioj.next.common.error;

import org.springframework.validation.FieldError;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

final class ValidationErrorMessages {
    private static final Map<String, String> FIELD_LABELS = Map.ofEntries(
            Map.entry("account", "账号"),
            Map.entry("password", "密码"),
            Map.entry("currentPassword", "当前密码"),
            Map.entry("newPassword", "新密码"),
            Map.entry("displayName", "显示名称"),
            Map.entry("email", "邮箱"),
            Map.entry("roles", "角色"),
            Map.entry("role", "身份"),
            Map.entry("title", "标题"),
            Map.entry("description", "描述"),
            Map.entry("difficulty", "难度"),
            Map.entry("statement", "题面"),
            Map.entry("notes", "说明"),
            Map.entry("testCases", "样例"),
            Map.entry("input", "输入"),
            Map.entry("expectedOutput", "期望输出"),
            Map.entry("language", "编程语言"),
            Map.entry("code", "代码"),
            Map.entry("problemId", "题目"),
            Map.entry("fileName", "文件名"),
            Map.entry("fileSizeBytes", "文件大小"),
            Map.entry("sha256", "SHA-256"),
            Map.entry("chunkSizeBytes", "分片大小"),
            Map.entry("totalChunks", "分片数量"),
            Map.entry("topic", "主题"),
            Map.entry("teachingGoal", "教学目标"),
            Map.entry("algorithm", "考察算法"),
            Map.entry("scenario", "背景/场景"),
            Map.entry("inputOutputSpec", "输入输出要求"),
            Map.entry("dataConstraints", "数据范围"),
            Map.entry("qualityRequirements", "质量要求"),
            Map.entry("standardSolutionLanguage", "标程语言"),
            Map.entry("standardSolutionCode", "标程代码"),
            Map.entry("testcaseGeneratorPython", "Python 测试脚本"),
            Map.entry("generationPlan", "生成计划"),
            Map.entry("feedback", "反馈"),
            Map.entry("reasonNote", "拒绝原因"),
            Map.entry("markdown", "导入内容"),
            Map.entry("mode", "模式"),
            Map.entry("name", "名称"),
            Map.entry("startAt", "开始时间"),
            Map.entry("endAt", "结束时间"),
            Map.entry("freezeAt", "封榜时间"),
            Map.entry("score", "分值"),
            Map.entry("sortOrder", "排序"),
            Map.entry("label", "题号"),
            Map.entry("reason", "原因"),
            Map.entry("teacherNote", "教师备注")
    );

    private ValidationErrorMessages() {
    }

    static String fieldLabel(String fieldPath) {
        if (fieldPath == null || fieldPath.isBlank()) {
            return "该字段";
        }
        String normalized = fieldPath;
        int dot = normalized.lastIndexOf('.');
        if (dot >= 0 && dot < normalized.length() - 1) {
            normalized = normalized.substring(dot + 1);
        }
        normalized = normalized.replaceAll("\\[\\d+]", "");
        return FIELD_LABELS.getOrDefault(normalized, normalized);
    }

    static String forFieldError(FieldError error) {
        String label = fieldLabel(error.getField());
        String code = error.getCode();
        if ("NotBlank".equals(code) || "NotEmpty".equals(code)) {
            return label + "不能为空。";
        }
        if ("NotNull".equals(code)) {
            return "请选择或填写" + label + "。";
        }
        if ("Email".equals(code)) {
            return label + "格式不正确。";
        }
        if ("Positive".equals(code)) {
            return label + "必须大于 0。";
        }
        if ("Min".equals(code)) {
            return label + "不能小于 " + firstNumber(error.getArguments(), "下限") + "。";
        }
        if ("Max".equals(code)) {
            return label + "不能大于 " + firstNumber(error.getArguments(), "上限") + "。";
        }
        if ("Size".equals(code)) {
            SizeBounds bounds = sizeBounds(error.getArguments());
            if (bounds.min() > 0 && bounds.max() < Integer.MAX_VALUE) {
                return label + "长度需在 " + bounds.min() + " 到 " + bounds.max() + " 个字符之间。";
            }
            if (bounds.min() > 0) {
                return label + "长度至少 " + bounds.min() + " 个字符。";
            }
            if (bounds.max() < Integer.MAX_VALUE) {
                return label + "长度不能超过 " + bounds.max() + " 个字符。";
            }
        }
        if ("Pattern".equals(code)) {
            if (error.getField().toLowerCase(Locale.ROOT).contains("sha")) {
                return label + "必须是 64 位十六进制字符串。";
            }
            return label + "格式不正确。";
        }
        return label + "填写不正确，请检查后重试。";
    }

    static Map<String, String> missingParameter(String name) {
        Map<String, String> details = new LinkedHashMap<>();
        details.put(name, fieldLabel(name) + "不能为空。");
        return details;
    }

    static Map<String, String> typeMismatch(String name, Class<?> requiredType) {
        Map<String, String> details = new LinkedHashMap<>();
        String type = requiredType == null ? "正确格式" : friendlyType(requiredType);
        details.put(name, fieldLabel(name) + "格式不正确，需要填写" + type + "。");
        return details;
    }

    private static String friendlyType(Class<?> type) {
        if (Number.class.isAssignableFrom(type) || type.isPrimitive()) {
            return "数字";
        }
        if (type.getName().contains("Instant") || type.getName().contains("LocalDate")) {
            return "时间";
        }
        if (type.isEnum()) {
            return "有效选项";
        }
        return "正确格式";
    }

    private static String firstNumber(Object[] args, String fallback) {
        if (args != null) {
            for (Object arg : args) {
                if (arg instanceof Number number) {
                    return String.valueOf(number.longValue());
                }
            }
        }
        return fallback;
    }

    private static SizeBounds sizeBounds(Object[] args) {
        int min = 0;
        int max = Integer.MAX_VALUE;
        if (args != null) {
            Number first = null;
            Number second = null;
            for (Object arg : args) {
                if (arg instanceof Number number) {
                    if (first == null) first = number;
                    else {
                        second = number;
                        break;
                    }
                }
            }
            if (first != null && second != null) {
                max = first.intValue();
                min = second.intValue();
            }
        }
        return new SizeBounds(min, max);
    }

    private record SizeBounds(int min, int max) {
    }
}
