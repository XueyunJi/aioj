package com.aioj.next.ai.agent.tool;

/**
 * Bijective mapping between internal dotted tool names ({@code context.search_exact})
 * and provider-safe wire names ({@code context__search_exact}). Kimi's function
 * name regex {@code ^[a-zA-Z_][a-zA-Z0-9-_]$} forbids dots; a double underscore
 * keeps the mapping reversible because internal names never contain "__"
 * (enforced by {@link ToolRegistry}).
 */
public final class ToolNameCodec {

    private ToolNameCodec() {
    }

    public static String toWire(String internalName) {
        return internalName == null ? null : internalName.replace(".", "__");
    }

    public static String toInternal(String wireName) {
        return wireName == null ? null : wireName.replace("__", ".");
    }
}
