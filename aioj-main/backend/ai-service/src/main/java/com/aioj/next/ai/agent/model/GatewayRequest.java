package com.aioj.next.ai.agent.model;

import com.aioj.next.ai.agent.tool.ToolDescriptor;

import java.util.List;

public record GatewayRequest(
        List<GatewayMessage> messages,
        List<ToolDescriptor> tools,
        ToolChoiceMode toolChoice,
        CallProfile profile
) {
    public GatewayRequest {
        messages = messages == null ? List.of() : List.copyOf(messages);
        tools = tools == null ? List.of() : List.copyOf(tools);
        toolChoice = toolChoice == null ? ToolChoiceMode.AUTO : toolChoice;
        profile = profile == null ? CallProfile.CHAT_STREAM : profile;
    }
}
