package com.aioj.next.ai.agent.tool;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Startup-validated registry of all {@link AgentTool} beans. Validation is
 * fail-fast: a tool that violates the contract (bad name, identity fields in
 * its input schema, unsupported schema keywords) prevents the service from
 * starting, because a half-guarded tool surface is worse than none.
 */
@Component
public class ToolRegistry {

    private static final Pattern NAME_PATTERN = Pattern.compile("^[a-z][a-z0-9_]*(\\.[a-z][a-z0-9_]*)+$");
    /** Fields a model must never be able to supply (design doc §4.1). */
    private static final Set<String> FORBIDDEN_INPUT_FIELDS = Set.of(
            "userid", "role", "isadmin", "visibility", "permission", "tenantid"
    );

    private final Map<String, AgentTool> toolsByName;

    public ToolRegistry(List<AgentTool> tools) {
        Map<String, AgentTool> index = new LinkedHashMap<>();
        List<String> violations = new ArrayList<>();
        for (AgentTool tool : tools) {
            ToolDescriptor descriptor = tool.descriptor();
            if (descriptor == null) {
                violations.add(tool.getClass().getName() + ": descriptor() returned null");
                continue;
            }
            validateDescriptor(tool, descriptor, violations);
            if (index.put(descriptor.name(), tool) != null) {
                violations.add(descriptor.name() + ": duplicate tool name");
            }
        }
        if (!violations.isEmpty()) {
            throw new IllegalStateException("Agent tool registry violations: " + String.join("; ", violations));
        }
        this.toolsByName = Map.copyOf(index);
    }

    private void validateDescriptor(AgentTool tool, ToolDescriptor descriptor, List<String> violations) {
        String name = descriptor.name();
        String who = tool.getClass().getSimpleName() + "(" + name + ")";
        if (name == null || !NAME_PATTERN.matcher(name).matches()) {
            violations.add(who + ": name must be dotted lowercase, e.g. context.search_exact");
        } else if (name.contains("__")) {
            violations.add(who + ": name must not contain consecutive underscores (reserved for wire-name encoding)");
        }
        if (descriptor.version() == null || descriptor.version().isBlank()) {
            violations.add(who + ": version is required");
        }
        if (descriptor.description() == null || descriptor.description().isBlank()) {
            violations.add(who + ": description is required");
        }
        if (descriptor.inputSchema() == null || !descriptor.inputSchema().isObject()
                || !"object".equals(descriptor.inputSchema().path("type").asText(null))) {
            violations.add(who + ": inputSchema must be a JSON object schema");
        } else {
            if (!ToolInputSchemaValidator.isSupportedSubset(descriptor.inputSchema())) {
                violations.add(who + ": inputSchema uses keywords outside the supported MFJS-safe subset");
            }
            collectForbiddenFields(descriptor.inputSchema(), "$", violations, who);
        }
        if (descriptor.maxResultTokens() <= 0) {
            violations.add(who + ": maxResultTokens must be positive");
        }
        if (descriptor.timeout() == null || descriptor.timeout().isNegative() || descriptor.timeout().isZero()) {
            violations.add(who + ": timeout must be a positive duration");
        }
    }

    private void collectForbiddenFields(com.fasterxml.jackson.databind.JsonNode schema, String path,
                                        List<String> violations, String who) {
        com.fasterxml.jackson.databind.JsonNode properties = schema.path("properties");
        if (properties.isObject()) {
            java.util.Iterator<Map.Entry<String, com.fasterxml.jackson.databind.JsonNode>> fields = properties.fields();
            while (fields.hasNext()) {
                Map.Entry<String, com.fasterxml.jackson.databind.JsonNode> entry = fields.next();
                if (FORBIDDEN_INPUT_FIELDS.contains(entry.getKey().toLowerCase(java.util.Locale.ROOT))) {
                    violations.add(who + ": input field " + path + "." + entry.getKey()
                            + " is an identity/permission field the model must never supply");
                }
                collectForbiddenFields(entry.getValue(), path + "." + entry.getKey(), violations, who);
            }
        }
        com.fasterxml.jackson.databind.JsonNode items = schema.path("items");
        if (items.isObject()) {
            collectForbiddenFields(items, path + "[]", violations, who);
        }
    }

    public AgentTool find(String name) {
        return toolsByName.get(name);
    }

    public List<ToolDescriptor> descriptors() {
        return toolsByName.values().stream()
                .map(AgentTool::descriptor)
                .sorted(Comparator.comparing(ToolDescriptor::name))
                .toList();
    }

    /** Tools visible to a caller holding the given scopes, sorted by name for prefix-cache stability. */
    public List<ToolDescriptor> descriptorsForScopes(Set<String> grantedScopes) {
        Set<String> granted = grantedScopes == null ? Set.of() : grantedScopes;
        return toolsByName.values().stream()
                .map(AgentTool::descriptor)
                .filter(descriptor -> granted.containsAll(descriptor.requiredScopes()))
                .sorted(Comparator.comparing(ToolDescriptor::name))
                .toList();
    }
}
