package com.finora.loan.util;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SearchPatternsTest {

    @Test
    void codePatternIsUpperCaseAndAcceptsUnaccentedContractPrefix() {
        assertThat(SearchPatterns.containsCode(" hs-2026 ")).isEqualTo("%HS-2026%");
        assertThat(SearchPatterns.containsCode("hd-20261109")).isEqualTo("%HĐ-20261109%");
        assertThat(SearchPatterns.containsCode("lc-3512bf")).isEqualTo("%LC-3512BF%");
    }

    @Test
    void textPatternIsLowerCaseForLowerColumnComparison() {
        assertThat(SearchPatterns.containsLower("Vay Kinh Doanh")).isEqualTo("%vay kinh doanh%");
    }

    @Test
    void likeWildcardsTypedByUsersAreEscaped() {
        assertThat(SearchPatterns.containsLower("50%_off!")).isEqualTo("%50!%!_off!!%");
        assertThat(SearchPatterns.containsCode("LA_%")).isEqualTo("%LA!_!%%");
    }

    @Test
    void blankMeansNoFilterAndLongInputIsCut() {
        assertThat(SearchPatterns.containsCode("   ")).isNull();
        assertThat(SearchPatterns.containsLower(null)).isNull();
        assertThat(SearchPatterns.trimmed("x".repeat(150))).hasSize(SearchPatterns.MAX_LENGTH);
    }
}
