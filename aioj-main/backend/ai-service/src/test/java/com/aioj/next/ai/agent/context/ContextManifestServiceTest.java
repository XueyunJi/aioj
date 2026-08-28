package com.aioj.next.ai.agent.context;

import com.aioj.next.ai.persistence.entity.AiContextManifestEntity;
import com.aioj.next.ai.persistence.mapper.AiContextManifestMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class ContextManifestServiceTest {

    private final AiContextManifestMapper mapper = mock(AiContextManifestMapper.class);
    private final ContextManifestService service = new ContextManifestService(mapper, new ObjectMapper());

    @Test
    void recordsSectionsHashAndWarnings() {
        List<ContextSection> sections = List.of(
                ContextSection.text(ContextSectionType.SYSTEM_POLICY, 10, true, TrustLevel.SYSTEM_POLICY, "sys"),
                ContextSection.text(ContextSectionType.CURRENT_USER_REQUEST, 90, true, TrustLevel.USER_PROVIDED, "hi"));
        service.record("turn-1", 99L, 1, "deepseek-v4-pro", BootstrapContextBuilder.PROMPT_VERSION,
                "ps-1", sections, "tools-hash", "context-hash", null, null, List.of("recent_turns_trimmed"));

        ArgumentCaptor<AiContextManifestEntity> captor = ArgumentCaptor.forClass(AiContextManifestEntity.class);
        verify(mapper).insert(captor.capture());
        AiContextManifestEntity entity = captor.getValue();
        assertThat(entity.getTurnId()).isEqualTo("turn-1");
        assertThat(entity.getAgentRunId()).isEqualTo(99L);
        assertThat(entity.getCallSeq()).isEqualTo(1);
        assertThat(entity.getPromptVersion()).isEqualTo(BootstrapContextBuilder.PROMPT_VERSION);
        assertThat(entity.getPolicySnapshotId()).isEqualTo("ps-1");
        assertThat(entity.getSectionsJson()).contains("SYSTEM_POLICY").contains("CURRENT_USER_REQUEST");
        assertThat(entity.getContextHash()).isEqualTo("context-hash");
        assertThat(entity.getWarningsJson()).contains("recent_turns_trimmed");
    }

    @Test
    void persistenceFailureIsSwallowed() {
        doThrow(new RuntimeException("db down")).when(mapper).insert(any(AiContextManifestEntity.class));
        service.record("turn-2", 99L, 2, "model", "pv", null, List.of(), null, null, null, null, List.of());
        // no exception escapes
    }

    @Test
    void sha256IsStable() {
        assertThat(ContextManifestService.sha256("abc"))
                .isEqualTo("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad");
        assertThat(ContextManifestService.sha256(null)).isEqualTo(ContextManifestService.sha256(""));
    }
}
