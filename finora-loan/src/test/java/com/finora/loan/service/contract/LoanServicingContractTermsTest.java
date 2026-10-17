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
    void dailyInterestContractExplainsHowEarlyInstallmentsAndPrepaymentsAreTreated() {
        String text = LoanServicingContractTerms.plainText(6, true);

        // Q3: ba câu bắt buộc cho hợp đồng core V4 (lãi theo ngày, ghi nhận kỳ đúng hạn, giảm lãi bằng trả trước).
        assertThat(text)
                .contains("lãi tính trên dư nợ gốc thực tế và số ngày thực tế")
                .contains("Khoản thanh toán kỳ trước ngày đến hạn được ghi nhận vào đúng ngày đến hạn")
                .contains("muốn giảm lãi, người vay chọn trả trước một phần")
                .contains("khoản của kỳ phải trả gần nhất (lãi chỉ tính đến ngày giao dịch)")
                // D16: khoản đang giữ hủy được (tiền về ví) và phải hủy trước khi trả trước/tất toán.
                .contains("có thể hủy khoản thanh toán để nhận lại tiền vào ví")
                .doesNotContain("nghĩa vụ đã đến hạn")
                .contains("không thu lãi của các tháng tương lai")
                .contains(LoanServicingContractTerms.DAILY_INTEREST_POLICY_VERSION)
                .contains(LoanServicingContractTerms.EARLY_SETTLEMENT_POLICY_VERSION)
                .doesNotContain("Product V3");
        assertThat(LoanServicingContractTerms.html(6, true))
                .contains("ghi nhận vào đúng ngày đến hạn")
                .contains(LoanServicingContractTerms.DAILY_INTEREST_POLICY_VERSION);
        // Hợp đồng core V1–V3 giữ nguyên văn bản đã dùng để ký.
        assertThat(LoanServicingContractTerms.plainText(6, false)).isEqualTo(LoanServicingContractTerms.plainText(6))
                .contains("Product V3 ưu tiên rút ngắn số kỳ")
                .doesNotContain("ghi nhận vào đúng ngày đến hạn");
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
