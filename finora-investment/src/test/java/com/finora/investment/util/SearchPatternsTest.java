package com.finora.investment.util;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SearchPatternsTest {

    @Test
    void codePatternIsUpperCaseEscapedAndAcceptsUnaccentedContractPrefix() {
        assertThat(SearchPatterns.containsCode(" hs-2026 ")).isEqualTo("%HS-2026%");
        assertThat(SearchPatterns.containsCode("hd-20261109")).isEqualTo("%HĐ-20261109%");
        assertThat(SearchPatterns.containsCode("tr_1%")).isEqualTo("%TR!_1!%%");
        assertThat(SearchPatterns.containsCode("  ")).isNull();
    }
}
