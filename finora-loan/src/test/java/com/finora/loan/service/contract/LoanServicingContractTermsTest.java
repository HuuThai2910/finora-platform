package com.finora.loan.service.contract;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class LoanServicingContractTermsTest {

    @Test
    void shortTermContractDisclosesThreeFeeBandsAndSplit() {
        assertThat(LoanServicingContractTerms.plainText(12))
                .contains("50% thời hạn đầu áp dụng 1%")
                .contains("sau 50% đến hết 70% thời hạn áp dụng 0,5%")
                .contains("sau 70% thời hạn áp dụng 0%")
                .contains("80% tổng phí được chia cho các nhà đầu tư")
                .contains("20% thuộc FINORA")
                .contains(LoanServicingContractTerms.POLICY_VERSION)
                .contains(LoanServicingContractTerms.EARLY_SETTLEMENT_POLICY_VERSION);
    }

    @Test
    void mediumTermContractDisclosesYearBasedFeeBands() {
        assertThat(LoanServicingContractTerms.plainText(24))
                .contains("trong 12 tháng đầu áp dụng 2%")
                .contains("từ tháng thứ 13 áp dụng 1,5%")
                .contains("Không thu lãi của các tháng tương lai")
                .contains("toàn bộ nhà đầu tư đang nắm Note đồng ý");
    }
}
