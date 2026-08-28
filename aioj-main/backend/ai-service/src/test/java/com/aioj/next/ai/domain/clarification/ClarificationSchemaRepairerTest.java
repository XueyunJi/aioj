package com.aioj.next.ai.domain.clarification;

import com.aioj.next.ai.domain.AiCompletion;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ClarificationSchemaRepairerTest {
    private final ClarificationSchemaRepairer repairer = new ClarificationSchemaRepairer();

    @Test
    void convertsChoiceWithoutOptionsToFreeText() {
        AiCompletion.Clarification repaired = repairer.repair(new AiCompletion.Clarification(
                "need-info",
                "blocking",
                "需要补充",
                "请选择你卡住的地方",
                new AiCompletion.ClarificationInput("single_choice", true, List.of(), false, null, ""),
                List.of(),
                "",
                null
        ));

        assertThat(repaired.input().kind()).isEqualTo("free_text");
        assertThat(repaired.input().allowCustom()).isTrue();
        assertThat(repaired.defaultAction()).isEqualTo("ask_user");
    }

    @Test
    void choosesCodeInputWhenPromptRequestsCodeWithoutOptions() {
        AiCompletion.Clarification repaired = repairer.repair(new AiCompletion.Clarification(
                "code",
                "blocking",
                "需要代码",
                "请粘贴当前代码和报错日志",
                new AiCompletion.ClarificationInput("", true, List.of(), false, null, ""),
                List.of(),
                "",
                null
        ));

        assertThat(repaired.input().kind()).isEqualTo("code");
        assertThat(repaired.input().customKind()).isEqualTo("code");
        assertThat(repaired.input().placeholder()).contains("代码");
    }

    @Test
    void choosesMixedWhenPromptRequestsCodeAndHasOptions() {
        AiCompletion.ClarificationOption option = new AiCompletion.ClarificationOption(
                "choice",
                "先看代码",
                "我会粘贴代码",
                null,
                null
        );
        AiCompletion.Clarification repaired = repairer.repair(new AiCompletion.Clarification(
                "mixed-code",
                "helpful",
                "补充上下文",
                "你可以选择方向，也可以粘贴代码",
                new AiCompletion.ClarificationInput("", false, List.of(option), false, null, ""),
                List.of(option),
                "",
                null
        ));

        assertThat(repaired.input().kind()).isEqualTo("mixed");
        assertThat(repaired.options()).hasSize(1);
        assertThat(repaired.input().allowCustom()).isTrue();
    }

    @Test
    void keepsAllowCustomSoFrontendCanRenderTextBox() {
        AiCompletion.ClarificationOption option = new AiCompletion.ClarificationOption(
                "choice",
                "直接继续",
                "按当前假设继续",
                null,
                null
        );
        AiCompletion.Clarification repaired = repairer.repair(new AiCompletion.Clarification(
                "custom",
                "helpful",
                "确认方向",
                "选择一个方向，或补充你的情况",
                new AiCompletion.ClarificationInput("single_choice", false, List.of(option), true, "free_text", "补充更多上下文"),
                List.of(option),
                "",
                null
        ));

        assertThat(repaired.input().kind()).isEqualTo("single_choice");
        assertThat(repaired.input().allowCustom()).isTrue();
        assertThat(repaired.input().customKind()).isEqualTo("free_text");
    }
}
