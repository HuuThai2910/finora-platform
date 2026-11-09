package com.finora.loan.service.contract.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.finora.loan.domain.application.entity.LoanApplication;
import com.finora.loan.domain.contract.model.BorrowerIdentity;
import com.finora.loan.domain.core.entity.ScheduleCalculationSnapshot;
import com.finora.loan.domain.product.enums.RepaymentMethod;
import com.finora.loan.service.contract.ContractDocumentRenderer;
import com.finora.loan.service.contract.ContractHeading;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ContractDocumentRendererImplTest {

    @Test
    void exactInputAlwaysProducesSameReadableUtf8Text() {
        LoanApplication application = mock(LoanApplication.class);
        when(application.getApplicationNumber()).thenReturn("LA-0123456789ABCDEF0123");
        when(application.getBorrowerId()).thenReturn("BORROWER-001");
        when(application.getRequestedAmount()).thenReturn(new BigDecimal("50000000.00"));
        when(application.getRequestedTermMonths()).thenReturn(12);
        when(application.getAnnualInterestRateSnapshot()).thenReturn(new BigDecimal("15.0000"));
        when(application.getRepaymentMethodSnapshot()).thenReturn(RepaymentMethod.ANNUITY);

        ScheduleCalculationSnapshot schedule = mock(ScheduleCalculationSnapshot.class);
        when(schedule.getExpectedDisbursementDate()).thenReturn(LocalDate.of(2026, 8, 20));
        when(schedule.getTotalInterest()).thenReturn(new BigDecimal("4000000.00"));
        when(schedule.getTotalFees()).thenReturn(BigDecimal.ZERO);
        when(schedule.getTotalPenalties()).thenReturn(BigDecimal.ZERO);
        when(schedule.getTotalRepayment()).thenReturn(new BigDecimal("54000000.00"));
        when(schedule.getFirstInstallment()).thenReturn(new BigDecimal("4500000.00"));
        when(schedule.getMaximumInstallment()).thenReturn(new BigDecimal("4500000.00"));
        when(schedule.getResponseHash()).thenReturn("a".repeat(64));
        when(schedule.getCalculationPolicyVersion()).thenReturn("FINERACT_1_15_SCHEDULE_V1");
        when(schedule.getPeriodsSnapshotJson()).thenReturn("""
                [{"period":1,"dueDate":"2026-09-20","principal":4000000.00,
                  "interest":500000.00,"fees":0,"penalties":0,
                  "totalDue":4500000.00,"outstandingBalance":46000000.00}]
                """);
        ContractDocumentRenderer renderer = new ContractDocumentRendererImpl(
                new ObjectMapper().findAndRegisterModules());
        Instant expiresAt = Instant.parse("2026-08-15T00:00:00Z");
        // Hợp đồng lập trước V39: chưa chụp danh tính, văn bản giữ cách ghi cũ.
        ContractHeading heading = new ContractHeading("LC-ABCDEF0123456789ABCD", null, null);

        String first = renderer.render(heading, application, schedule,
                "LOAN_TERMS_V1", "CLICK_WRAP_TEXT_V2", expiresAt);
        String second = renderer.render(heading, application, schedule,
                "LOAN_TERMS_V1", "CLICK_WRAP_TEXT_V2", expiresAt);

        assertThat(second).isEqualTo(first);
        assertThat(first)
                .contains("HỢP ĐỒNG VAY FINORA")
                .contains("Số tiền vay: 50.000.000 đồng")
                .contains("Lãi suất cơ sở đã công bố: 15%/năm")
                .contains("Lãi suất áp dụng cuối: 15%/năm")
                .contains("Kỳ 1 — hạn 2026-09-20: gốc 4.000.000 đồng; lãi 500.000 đồng")
                .contains("Không thu lãi của các tháng tương lai")
                .contains("80% tổng phí được chia cho các nhà đầu tư")
                .contains("toàn bộ nhà đầu tư đang nắm Note đồng ý")
                .contains("Khoản vay từ 12 tháng trở xuống")
                .contains("Phiên bản tài liệu: CLICK_WRAP_TEXT_V2")
                .contains("Mã hợp đồng: LC-ABCDEF0123456789ABCD")
                .contains("Mã tham chiếu người vay: BORROWER-001")
                .endsWith("\n");
    }

    @Test
    void newContractPrintsDisplayCodesAndCapturedBorrowerIdentity() {
        ContractDocumentRenderer renderer = new ContractDocumentRendererImpl(
                new ObjectMapper().findAndRegisterModules());
        ContractHeading heading = new ContractHeading("LC-ABCDEF0123456789ABCD", "HĐ-20261109-K7QM",
                new BorrowerIdentity("Nguyễn Văn An", "********1234", Instant.parse("2026-11-09T02:00:00Z")));

        String text = renderer.render(heading, application(), schedule(), "LOAN_TERMS_V1", "CLICK_WRAP_TEXT_V2",
                Instant.parse("2026-11-16T00:00:00Z"));

        assertThat(text)
                .contains("Mã hợp đồng: HĐ-20261109-K7QM")
                .contains("Mã hồ sơ vay: HS-20261108-3RXA")
                .contains("Bên vay: Nguyễn Văn An")
                .contains("Số CCCD bên vay: ********1234")
                .contains("Mã tra cứu hệ thống: LC-ABCDEF0123456789ABCD / LA-0123456789ABCDEF0123")
                .doesNotContain("Mã tham chiếu người vay");
    }

    @Test
    void borrowerWithoutProfileIsMarkedNotUpdatedInsteadOfGuessed() {
        ContractDocumentRenderer renderer = new ContractDocumentRendererImpl(
                new ObjectMapper().findAndRegisterModules());
        ContractHeading heading = new ContractHeading("LC-ABCDEF0123456789ABCD", "HĐ-20261109-K7QM",
                new BorrowerIdentity(null, null, Instant.parse("2026-11-09T02:00:00Z")));

        String text = renderer.render(heading, application(), schedule(), "LOAN_TERMS_V1", "CLICK_WRAP_TEXT_V2",
                Instant.parse("2026-11-16T00:00:00Z"));

        assertThat(text)
                .contains("Bên vay: Chưa cập nhật hồ sơ")
                .contains("Số CCCD bên vay: Chưa cập nhật")
                .doesNotContain("BORROWER-001");
    }

    private static LoanApplication application() {
        LoanApplication application = mock(LoanApplication.class);
        when(application.getApplicationNumber()).thenReturn("LA-0123456789ABCDEF0123");
        when(application.getApplicationCode()).thenReturn("HS-20261108-3RXA");
        when(application.getBorrowerId()).thenReturn("BORROWER-001");
        when(application.getRequestedAmount()).thenReturn(new BigDecimal("50000000.00"));
        when(application.getRequestedTermMonths()).thenReturn(12);
        when(application.getAnnualInterestRateSnapshot()).thenReturn(new BigDecimal("15.0000"));
        when(application.getRepaymentMethodSnapshot()).thenReturn(RepaymentMethod.ANNUITY);
        return application;
    }

    private static ScheduleCalculationSnapshot schedule() {
        ScheduleCalculationSnapshot schedule = mock(ScheduleCalculationSnapshot.class);
        when(schedule.getExpectedDisbursementDate()).thenReturn(LocalDate.of(2026, 11, 20));
        when(schedule.getTotalInterest()).thenReturn(new BigDecimal("4000000.00"));
        when(schedule.getTotalFees()).thenReturn(BigDecimal.ZERO);
        when(schedule.getTotalPenalties()).thenReturn(BigDecimal.ZERO);
        when(schedule.getTotalRepayment()).thenReturn(new BigDecimal("54000000.00"));
        when(schedule.getFirstInstallment()).thenReturn(new BigDecimal("4500000.00"));
        when(schedule.getMaximumInstallment()).thenReturn(new BigDecimal("4500000.00"));
        when(schedule.getResponseHash()).thenReturn("a".repeat(64));
        when(schedule.getCalculationPolicyVersion()).thenReturn("FINERACT_1_15_SCHEDULE_V1");
        when(schedule.getPeriodsSnapshotJson()).thenReturn("""
                [{"period":1,"dueDate":"2026-12-20","principal":4000000.00,
                  "interest":500000.00,"fees":0,"penalties":0,
                  "totalDue":4500000.00,"outstandingBalance":46000000.00}]
                """);
        return schedule;
    }
}
