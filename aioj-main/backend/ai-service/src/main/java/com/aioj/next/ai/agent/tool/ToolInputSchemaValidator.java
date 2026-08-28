package com.aioj.next.ai.agent.tool;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * Minimal JSON-Schema validator for the MFJS-safe subset used by tool
 * descriptors (type/properties/required/items/enum/additionalProperties,
 * minLength/maxLength). No external schema dependency is introduced; schemas
 * outside this subset are rejected at registry time.
 */
public final class ToolInputSchemaValidator {

    private ToolInputSchemaValidator() {
    }

    public static List<String> validate(JsonNode schema, JsonNode instance) {
        List<String> errors = new ArrayList<>();
        if (schema == null || schema.isNull()) {
            return errors;
        }
        validateNode(schema, instance, "$", errors);
        return errors;
    }

    /** Whether the schema only uses constructs this validator (and MFJS strict mode) understands. */
    public static boolean isSupportedSubset(JsonNode schema) {
        if (schema == null || schema.isNull()) {
            return false;
        }
        List<String> unsupported = new ArrayList<>();
        collectUnsupported(schema, "$", unsupported);
        return unsupported.isEmpty();
    }

    private static final List<String> UNSUPPORTED_KEYS = List.of(
            "oneOf", "anyOf", "allOf", "not", "$ref", "patternProperties", "dependencies",
            "dependentRequired", "dependentSchemas", "if", "then", "else", "contains"
    );

    private static void collectUnsupported(JsonNode schema, String path, List<String> unsupported) {
        if (!schema.isObject()) {
            return;
        }
        for (String key : UNSUPPORTED_KEYS) {
            if (schema.has(key)) {
                unsupported.add(path + ": unsupported keyword " + key);
            }
        }
        JsonNode properties = schema.path("properties");
        if (properties.isObject()) {
            Iterator<Map.Entry<String, JsonNode>> fields = properties.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> entry = fields.next();
                collectUnsupported(entry.getValue(), path + "." + entry.getKey(), unsupported);
            }
        }
        JsonNode items = schema.path("items");
        if (items.isObject()) {
            collectUnsupported(items, path + "[]", unsupported);
        }
    }

    private static void validateNode(JsonNode schema, JsonNode value, String path, List<String> errors) {
        if (value == null || value.isNull()) {
            if (schema.has("type")) {
                errors.add(path + ": must not be null");
            }
            return;
        }
        if (schema.has("enum")) {
            boolean matches = false;
            for (JsonNode candidate : schema.get("enum")) {
                if (candidate.equals(value)) {
                    matches = true;
                    break;
                }
            }
            if (!matches) {
                errors.add(path + ": value is not one of the allowed enum values");
                return;
            }
        }
        String type = schema.path("type").isTextual() ? schema.get("type").asText() : null;
        if (type == null) {
            return;
        }
        switch (type) {
            case "object" -> validateObject(schema, value, path, errors);
            case "array" -> validateArray(schema, value, path, errors);
            case "string" -> {
                if (!value.isTextual()) {
                    errors.add(path + ": expected string");
                } else {
                    if (schema.has("minLength") && value.asText().length() < schema.get("minLength").asInt()) {
                        errors.add(path + ": shorter than minLength " + schema.get("minLength").asInt());
                    }
                    if (schema.has("maxLength") && value.asText().length() > schema.get("maxLength").asInt()) {
                        errors.add(path + ": longer than maxLength " + schema.get("maxLength").asInt());
                    }
                }
            }
            case "integer" -> {
                if (!value.isIntegralNumber()) {
                    errors.add(path + ": expected integer");
                }
            }
            case "number" -> {
                if (!value.isNumber()) {
                    errors.add(path + ": expected number");
                }
            }
            case "boolean" -> {
                if (!value.isBoolean()) {
                    errors.add(path + ": expected boolean");
                }
            }
            default -> {
                // Unknown types are rejected at registry time; be permissive here.
            }
        }
    }

    private static void validateObject(JsonNode schema, JsonNode value, String path, List<String> errors) {
        if (!value.isObject()) {
            errors.add(path + ": expected object");
            return;
        }
        JsonNode required = schema.path("required");
        if (required.isArray()) {
            for (JsonNode field : required) {
                if (field.isTextual() && !value.has(field.asText())) {
                    errors.add(path + "." + field.asText() + ": is required");
                }
            }
        }
        JsonNode properties = schema.path("properties");
        boolean additionalAllowed = !schema.has("additionalProperties") || schema.get("additionalProperties").asBoolean(true);
        Iterator<Map.Entry<String, JsonNode>> fields = value.fields();
        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> entry = fields.next();
            JsonNode childSchema = properties.isObject() ? properties.get(entry.getKey()) : null;
            if (childSchema != null) {
                validateNode(childSchema, entry.getValue(), path + "." + entry.getKey(), errors);
            } else if (!additionalAllowed) {
                errors.add(path + "." + entry.getKey() + ": is not allowed");
            }
        }
    }

    private static void validateArray(JsonNode schema, JsonNode value, String path, List<String> errors) {
        if (!value.isArray()) {
            errors.add(path + ": expected array");
            return;
        }
        JsonNode items = schema.path("items");
        if (!items.isObject()) {
            return;
        }
        for (int i = 0; i < value.size(); i++) {
            validateNode(items, value.get(i), path + "[" + i + "]", errors);
        }
    }
}
