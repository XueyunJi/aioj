package com.aioj.next.ai.agent.tool;

/**
 * Pointer back to the authoritative source a tool result derives from.
 * Models must fetch the source before relying on exact details.
 */
public record SourceRef(String type, String id) {
}
