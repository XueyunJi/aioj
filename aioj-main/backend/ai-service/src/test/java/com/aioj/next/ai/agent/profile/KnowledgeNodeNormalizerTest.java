package com.aioj.next.ai.agent.profile;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class KnowledgeNodeNormalizerTest {

    @Test
    void englishPhrasesAreSnakeCased() {
        assertThat(KnowledgeNodeNormalizer.normalize("Binary Search")).isEqualTo("binary_search");
        assertThat(KnowledgeNodeNormalizer.normalize("  Dynamic Programming  ")).isEqualTo("dynamic_programming");
        assertThat(KnowledgeNodeNormalizer.normalize("BFS/DFS")).isEqualTo("bfs_dfs");
        assertThat(KnowledgeNodeNormalizer.normalize("two--pointers")).isEqualTo("two_pointers");
    }

    @Test
    void cjkIsPreservedVerbatim() {
        assertThat(KnowledgeNodeNormalizer.normalize("位运算")).isEqualTo("位运算");
        assertThat(KnowledgeNodeNormalizer.normalize("动态规划")).isEqualTo("动态规划");
    }

    @Test
    void mixedCjkAndLatinNormalizeTogether() {
        assertThat(KnowledgeNodeNormalizer.normalize("BFS/DFS 遍历")).isEqualTo("bfs_dfs_遍历");
        assertThat(KnowledgeNodeNormalizer.normalize("线段树 Segment Tree")).isEqualTo("线段树_segment_tree");
        assertThat(KnowledgeNodeNormalizer.normalize("二分查找(Binary Search)")).isEqualTo("二分查找_binary_search");
    }

    @Test
    void separatorsCollapseAndTrim() {
        assertThat(KnowledgeNodeNormalizer.normalize("__graph__theory__")).isEqualTo("graph_theory");
        assertThat(KnowledgeNodeNormalizer.normalize("a  -  b")).isEqualTo("a_b");
        assertThat(KnowledgeNodeNormalizer.normalize("_")).isEmpty();
    }

    @Test
    void nullAndBlankReturnEmpty() {
        assertThat(KnowledgeNodeNormalizer.normalize(null)).isEmpty();
        assertThat(KnowledgeNodeNormalizer.normalize("")).isEmpty();
        assertThat(KnowledgeNodeNormalizer.normalize("   ")).isEmpty();
    }

    @Test
    void resultIsCappedAt100Chars() {
        String longNode = "a".repeat(150);
        String normalized = KnowledgeNodeNormalizer.normalize(longNode);
        assertThat(normalized).hasSize(100);

        String longMixed = KnowledgeNodeNormalizer.normalize("节点 " + "b".repeat(150));
        assertThat(longMixed).hasSize(100);
    }

    @Test
    void normalizationIsIdempotent() {
        String once = KnowledgeNodeNormalizer.normalize("Binary Search 位运算");
        assertThat(KnowledgeNodeNormalizer.normalize(once)).isEqualTo(once);
    }
}
