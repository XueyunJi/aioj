package com.aioj.next.ai.agent.runtime;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class LoopBudgetTest {

    private final LoopBudget budget = new LoopBudget(8, 6, 3, 3);

    @Test
    void contextToolsKeepTheirCategories() {
        assertThat(budget.categoryOf("context.search_exact")).isEqualTo("search");
        assertThat(budget.categoryOf("context.fetch_thread")).isEqualTo("fetch");
    }

    @Test
    void memoryToolsShareContextCategories() {
        assertThat(budget.categoryOf("memory.search_claims")).isEqualTo("search");
        assertThat(budget.categoryOf("memory.fetch_evidence")).isEqualTo("fetch");
    }

    @Test
    void memoryToolsConsumeTheSameCategoryBudget() {
        assertThat(budget.categoryExhausted("memory.search_claims", 3, 0)).isTrue();
        assertThat(budget.categoryExhausted("memory.search_claims", 2, 0)).isFalse();
        assertThat(budget.categoryExhausted("memory.fetch_evidence", 0, 3)).isTrue();
        assertThat(budget.categoryExhausted("memory.fetch_evidence", 0, 2)).isFalse();
    }

    @Test
    void profileSearchSharesTheSearchCategory() {
        assertThat(budget.categoryOf("profile.search")).isEqualTo("search");
        assertThat(budget.categoryExhausted("profile.search", 3, 0)).isTrue();
        assertThat(budget.categoryExhausted("profile.search", 2, 0)).isFalse();
    }

    @Test
    void unknownAndNullToolsAreOther() {
        assertThat(budget.categoryOf("judge.run")).isEqualTo("other");
        assertThat(budget.categoryOf(null)).isEqualTo("other");
    }
}
