package com.aioj.next.ai.agent.tool;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ToolNameCodecTest {

    @Test
    void dottedNamesRoundTripThroughWireEncoding() {
        assertThat(ToolNameCodec.toWire("context.search_exact")).isEqualTo("context__search_exact");
        assertThat(ToolNameCodec.toInternal("context__search_exact")).isEqualTo("context.search_exact");
        assertThat(ToolNameCodec.toInternal(ToolNameCodec.toWire("contest.snapshot_fetch"))).isEqualTo("contest.snapshot_fetch");
    }

    @Test
    void namesWithoutDotsPassThroughUnchanged() {
        assertThat(ToolNameCodec.toWire("plainname")).isEqualTo("plainname");
        assertThat(ToolNameCodec.toInternal("plainname")).isEqualTo("plainname");
    }

    @Test
    void nullPassesThrough() {
        assertThat(ToolNameCodec.toWire(null)).isNull();
        assertThat(ToolNameCodec.toInternal(null)).isNull();
    }
}
