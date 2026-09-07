package com.finora.loan.service.contract;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.finora.loan.domain.application.LoanApplication;
import com.finora.loan.domain.application.LoanPurpose;
import com.finora.loan.domain.contract.ContractPdfArtifactType;
import com.finora.loan.domain.core.ScheduleCalculationSnapshot;
import com.finora.loan.domain.product.RepaymentMethod;
import com.finora.loan.support.HashingService;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ContractPdfRendererTest {

    @Test
    void rendersReadableVietnamesePdfFromFrozenLoanAndSchedule() throws Exception {
        LoanApplication application = application();
        ScheduleCalculationSnapshot schedule = schedule();
        HashingService hashing = new HashingService(new ObjectMapper().findAndRegisterModules());
        ContractPdfRenderer renderer = new ContractPdfRenderer(
                new ObjectMapper().findAndRegisterModules(), hashing);

        ContractPdfArtifact artifact = renderer.renderSignable(
                "LC-3512BFF2CC0B4DB8AEA3", application, schedule,
                "LOAN_TERMS_V1", Instant.parse("2026-10-08T10:00:00Z"));

        assertThat(artifact.artifactType()).isEqualTo(ContractPdfArtifactType.SIGNABLE);
        assertThat(new String(artifact.content(), 0, 4, StandardCharsets.US_ASCII)).isEqualTo("%PDF");
        assertThat(artifact.contentHash()).isEqualTo(hashing.sha256Bytes(artifact.content()));

        Path output = Path.of("target", "test-output", "finora-contract-backend-preview.pdf");
        Files.createDirectories(output.getParent());
        Files.write(output, artifact.content());

        try (PDDocument pdf = PDDocument.load(artifact.content())) {
            String text = new PDFTextStripper().getText(pdf);
            assertThat(pdf.getNumberOfPages()).isGreaterThanOrEqualTo(3);
            assertThat(text)
                    .contains("CỘNG HÒA XÃ HỘI CHỦ NGHĨA VIỆT NAM")
                    .contains("HỢP ĐỒNG CHO VAY")
                    .contains("10.000.000 đồng")
                    .contains("13.5%/năm")
                    .contains("LỊCH TRẢ NỢ DỰ KIẾN")
                    .contains("CHƯA TÍCH HỢP");
        }
    }

    private LoanApplication application() {
        LoanApplication application = mock(LoanApplication.class);
        when(application.getApplicationNumber()).thenReturn("LA-7FA66C3814FE443887DB");
        when(application.getBorrowerId()).thenReturn("BORROWER-001");
        when(application.getRequestedAmount()).thenReturn(new BigDecimal("10000000.00"));
        when(application.getRequestedTermMonths()).thenReturn(6);
        when(application.getAnnualInterestRateSnapshot()).thenReturn(new BigDecimal("12.5000"));
        when(application.getFinalAnnualInterestRate()).thenReturn(new BigDecimal("13.5000"));
        when(application.getRepaymentMethodSnapshot()).thenReturn(RepaymentMethod.ANNUITY);
        when(application.getPurposeCode()).thenReturn(LoanPurpose.EDUCATION);
        return application;
    }

    private ScheduleCalculationSnapshot schedule() {
        ScheduleCalculationSnapshot schedule = mock(ScheduleCalculationSnapshot.class);
        when(schedule.getExpectedDisbursementDate()).thenReturn(LocalDate.of(2026, 10, 6));
        when(schedule.getTotalInterest()).thenReturn(new BigDecimal("396000.00"));
        when(schedule.getTotalFees()).thenReturn(BigDecimal.ZERO);
        when(schedule.getTotalPenalties()).thenReturn(BigDecimal.ZERO);
        when(schedule.getTotalRepayment()).thenReturn(new BigDecimal("10396000.00"));
        when(schedule.getFirstInstallment()).thenReturn(new BigDecimal("1733000.00"));
        when(schedule.getMaximumInstallment()).thenReturn(new BigDecimal("1733000.00"));
        when(schedule.getResponseHash()).thenReturn("a".repeat(64));
        when(schedule.getCalculationPolicyVersion()).thenReturn("FINERACT_1_15_SCHEDULE_V1");
        when(schedule.getPeriodsSnapshotJson()).thenReturn("""
                [
                  {"period":1,"dueDate":"2026-11-06","principal":1620500,"interest":112500,"fees":0,"penalties":0,"totalDue":1733000,"outstandingBalance":8379500},
                  {"period":2,"dueDate":"2026-12-06","principal":1638700,"interest":94300,"fees":0,"penalties":0,"totalDue":1733000,"outstandingBalance":6740800},
                  {"period":3,"dueDate":"2027-01-06","principal":1657100,"interest":75900,"fees":0,"penalties":0,"totalDue":1733000,"outstandingBalance":5083700},
                  {"period":4,"dueDate":"2027-02-06","principal":1675700,"interest":57300,"fees":0,"penalties":0,"totalDue":1733000,"outstandingBalance":3408000},
                  {"period":5,"dueDate":"2027-03-06","principal":1694700,"interest":38300,"fees":0,"penalties":0,"totalDue":1733000,"outstandingBalance":1713300},
                  {"period":6,"dueDate":"2027-04-06","principal":1713300,"interest":19300,"fees":0,"penalties":0,"totalDue":1732600,"outstandingBalance":0}
                ]
                """);
        return schedule;
    }
}
