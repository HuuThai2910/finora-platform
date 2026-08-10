package com.finora.loan.service.contract;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.finora.loan.domain.application.LoanApplication;
import com.finora.loan.domain.core.ScheduleCalculationSnapshot;
import com.finora.loan.domain.product.RepaymentMethod;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ContractDocumentRendererTest {

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
        ContractDocumentRenderer renderer = new ContractDocumentRenderer(
                new ObjectMapper().findAndRegisterModules());
        Instant expiresAt = Instant.parse("2026-08-15T00:00:00Z");

        String first = renderer.render("LC-ABCDEF0123456789ABCD", application, schedule,
                "LOAN_TERMS_V1", "CLICK_WRAP_TEXT_V2", expiresAt);
        String second = renderer.render("LC-ABCDEF0123456789ABCD", application, schedule,
                "LOAN_TERMS_V1", "CLICK_WRAP_TEXT_V2", expiresAt);

        assertThat(second).isEqualTo(first);
        assertThat(first)
                .contains("HỢP ĐỒNG VAY FINORA")
                .contains("Số tiền vay: 50.000.000 đồng")
                .contains("Lãi suất cố định: 15%/năm")
                .contains("Kỳ 1 — hạn 2026-09-20: gốc 4.000.000 đồng; lãi 500.000 đồng")
                .contains("Phiên bản tài liệu: CLICK_WRAP_TEXT_V2")
                .endsWith("\n");
    }
}
