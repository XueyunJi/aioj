package com.aioj.next.ai.agent.config;

import com.aioj.next.ai.domain.AiModelConfigService;
import com.aioj.next.ai.domain.AiModelEffectiveConfig;
import com.aioj.next.ai.domain.AiModelScope;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/**
 * Startup visibility for the Agent Core V3 model plane: prints the effective
 * TEXT_GENERATION and EMBEDDING configs (provider/model/thinking/source) so an
 * env takeover or a stale DB row is obvious from the boot log. Secrets are
 * never printed.
 */
@Component
public class AgentModelConfigLogger implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(AgentModelConfigLogger.class);

    private final AiModelConfigService configService;

    public AgentModelConfigLogger(AiModelConfigService configService) {
        this.configService = configService;
    }

    @Override
    public void run(ApplicationArguments args) {
        logEffective(AiModelScope.TEXT_GENERATION);
        logEffective(AiModelScope.EMBEDDING);
    }

    private void logEffective(AiModelScope scope) {
        try {
            AiModelEffectiveConfig config = configService.effectiveConfig(scope);
            log.info("AgentCore model config scope={} source={} provider={} model={} baseUrl={} thinking={} reasoningEffort={} enabled={} hasApiKey={}",
                    scope,
                    config.source(),
                    config.provider(),
                    config.model(),
                    config.baseUrl(),
                    config.thinkingEnabled(),
                    config.reasoningEffort(),
                    config.enabled(),
                    config.hasApiKey());
        } catch (RuntimeException ex) {
            log.warn("AgentCore model config scope={} could not be resolved at startup: {}", scope, ex.getMessage());
        }
    }
}
