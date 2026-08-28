package com.aioj.next.ai.agent.tool;

import org.springframework.stereotype.Service;

import java.util.Set;
import java.util.UUID;

/**
 * Per-call authorization: every tool invocation is independently authorized
 * against the server-generated execution context (never model claims).
 * P0 scope: required-scope checks. Contest/resource ABAC arrives in P3.
 */
@Service
public class ToolAuthorizationService {

    public record AuthorizationDecision(boolean allowed, String reasonCode, String decisionId) {
    }

    public AuthorizationDecision authorize(ToolDescriptor descriptor, ToolExecutionContext context) {
        String decisionId = "pd-" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        Set<String> required = descriptor.requiredScopes();
        Set<String> granted = context.grantedScopes();
        if (required.isEmpty() || granted.containsAll(required)) {
            return new AuthorizationDecision(true, "ALLOW", decisionId);
        }
        return new AuthorizationDecision(false, "MISSING_SCOPE", decisionId);
    }
}
