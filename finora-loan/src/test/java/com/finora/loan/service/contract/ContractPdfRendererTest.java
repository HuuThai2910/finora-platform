package com.finora.loan.service.contract;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.finora.loan.domain.application.LoanApplication;
import com.finora.loan.domain.application.LoanPurpose;
import com.finora.loan.domain.contract.ContractParty;
import com.finora.loan.domain.contract.ContractPartyType;
import com.finora.loan.domain.contract.ContractPdfArtifactType;
import com.finora.loan.domain.contract.SignatureProviderType;
import com.finora.loan.domain.contract.LoanContract;
import com.finora.loan.domain.contract.SignatureMethod;
import com.finora.loan.domain.core.ScheduleCalculationSnapshot;
import com.finora.loan.domain.product.RepaymentMethod;
import com.finora.loan.support.HashingService;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
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
                    .contains("các tháng tương lai")
                    .contains("80%")
                    .contains("nhà đầu tư đang nắm Note")
                    .contains("CHƯA TÍCH HỢP");
        }
    }

    @Test
    void signedMultiPartyReceiptShowsCompletedLenderEvidence() throws Exception {
        HashingService hashing = new HashingService(new ObjectMapper().findAndRegisterModules());
        ContractPdfRenderer renderer = new ContractPdfRenderer(
                new ObjectMapper().findAndRegisterModules(), hashing);
        LoanContract contract = mock(LoanContract.class);
        when(contract.getContractNumber()).thenReturn("LC-MULTI-PARTY-001");
        when(contract.getTermsVersion()).thenReturn("LOAN_TERMS_V1");
        when(contract.getExpiresAt()).thenReturn(Instant.parse("2026-10-08T10:00:00Z"));
        when(contract.getSignedBy()).thenReturn("BORROWER-001");
        when(contract.getSignedAt()).thenReturn(Instant.parse("2026-10-01T10:00:00Z"));
        when(contract.getSignatureMethod()).thenReturn(SignatureMethod.VNPT_SMART_CA);

        ContractParty lender = mock(ContractParty.class);
        when(lender.getPartyType()).thenReturn(com.finora.loan.domain.contract.ContractPartyType.LENDER);
        when(lender.getPartyId()).thenReturn("INVESTOR-001");
        when(lender.getAllocationAmount()).thenReturn(new BigDecimal("10000000.00"));
        when(lender.getStatus()).thenReturn(com.finora.loan.domain.contract.ContractPartyStatus.SIGNED);
        when(lender.getSignatureProvider()).thenReturn(
                com.finora.loan.domain.contract.SignatureProviderType.VNPT_SMART_CA);
        when(lender.getSignatureMethod()).thenReturn(SignatureMethod.VNPT_SMART_CA);
        when(lender.getSignedAt()).thenReturn(Instant.parse("2026-10-01T09:00:00Z"));
        when(lender.getSignatureEvidenceHash()).thenReturn("c".repeat(64));

        ContractPdfArtifact artifact = renderer.renderSignedReceipt(
                contract, application(), schedule(), "b".repeat(64), List.of(lender));

        try (PDDocument pdf = PDDocument.load(artifact.content())) {
            String text = new PDFTextStripper().getText(pdf);
            assertThat(text)
                    .contains("ĐÃ KÝ")
                    .contains("INVESTOR-001")
                    .contains("VNPT_SMART_CA")
                    .contains("BẰNG CHỨNG CHỮ KÝ CÁC BÊN CHO VAY")
                    .doesNotContain("CHƯA TÍCH HỢP");
        }
    }

    @Test
    void padesSignableDescribesHiddenSequentialSignaturesUnderItsOwnVersion() throws Exception {
        HashingService hashing = new HashingService(new ObjectMapper().findAndRegisterModules());
        ContractPdfRenderer renderer = new ContractPdfRenderer(new ObjectMapper().findAndRegisterModules(), hashing);
        List<FundedContractAllocation> allocations = List.of(
                new FundedContractAllocation(101L, "INVESTOR-001", new BigDecimal("6000000.00"),
                        new BigDecimal("60.000000"), "HOLD-101"),
                new FundedContractAllocation(102L, "INVESTOR-002", new BigDecimal("4000000.00"),
                        new BigDecimal("40.000000"), "HOLD-102"));

        ContractPdfArtifact legacy = renderer.renderFundedSignable(
                "LC-PADES-001", application(), schedule(), "LOAN_TERMS_V2", Instant.parse("2026-10-08T10:00:00Z"),
                allocations, 1L, "d".repeat(64));
        ContractPdfArtifact pades = renderer.renderFundedSignable(
                "LC-PADES-001", application(), schedule(), "LOAN_TERMS_V2", Instant.parse("2026-10-08T10:00:00Z"),
                allocations, 1L, "d".repeat(64), true);

        assertThat(legacy.documentVersion()).isEqualTo(ContractPdfRenderer.DOCUMENT_VERSION);
        assertThat(pades.documentVersion()).isEqualTo(ContractPdfRenderer.DOCUMENT_VERSION + "_PADES");
        Path output = Path.of("target", "test-output", "finora-pades-signable-preview.pdf");
        Files.createDirectories(output.getParent());
        Files.write(output, pades.content());
        try (PDDocument pdf = PDDocument.load(pades.content())) {
            // Gộp khoảng trắng: cụm từ có thể bị ngắt dòng trong PDF.
            String text = new PDFTextStripper().getText(pdf).replaceAll("\\s+", " ");
            assertThat(text)
                    .contains("KÝ SỐ TRƯỚC")
                    .contains("KÝ SỐ SAU CÙNG")
                    .contains("PAdES-B-B")
                    .contains("trang biên nhận")
                    .contains("CONTRACT_PDF_V2_PADES")
                    .doesNotContain("không tự chứng minh chữ ký PAdES")
                    .doesNotContain("CHƯA TÍCH HỢP");
        }
        try (PDDocument pdf = PDDocument.load(legacy.content())) {
            assertThat(new PDFTextStripper().getText(pdf).replaceAll("\\s+", " "))
                    .contains("không tự chứng minh chữ ký PAdES");
        }
    }

    @Test
    void signatureReceiptListsEverySignatureWithoutSubjectIdentifiers() throws Exception {
        HashingService hashing = new HashingService(new ObjectMapper().findAndRegisterModules());
        ContractPdfRenderer renderer = new ContractPdfRenderer(new ObjectMapper().findAndRegisterModules(), hashing);
        ContractSignatureReceipt receipt = new ContractSignatureReceipt(
                "LC-PADES-001", "LA-7FA66C3814FE443887DB", "a".repeat(64), "CONTRACT_PDF_V3_PADES",
                "b".repeat(64), 245_678, Instant.parse("2026-10-17T03:00:00Z"), List.of(
                new ContractSignatureReceipt.Row(1, ContractPartyType.LENDER, "INVESTOR-001",
                        Instant.parse("2026-10-17T02:00:00Z"), SignatureProviderType.MOCK,
                        SignatureMethod.CLICK_WRAP_MVP, "FINORA DEMO - KHONG CO GIA TRI PHAP LY", "1a2b",
                        "FINORA DEMO - KHONG CO GIA TRI PHAP LY", "c".repeat(64), "d".repeat(64), 120_000),
                new ContractSignatureReceipt.Row(2, ContractPartyType.BORROWER, "BORROWER-001",
                        Instant.parse("2026-10-17T02:30:00Z"), SignatureProviderType.MOCK,
                        SignatureMethod.CLICK_WRAP_MVP, "FINORA DEMO - KHONG CO GIA TRI PHAP LY", "1a2b",
                        "FINORA DEMO - KHONG CO GIA TRI PHAP LY", "c".repeat(64), "b".repeat(64), 245_678)));

        ContractPdfArtifact artifact = renderer.renderSignatureReceipt(receipt);
        Path output = Path.of("target", "test-output", "finora-pades-receipt-preview.pdf");
        Files.createDirectories(output.getParent());
        Files.write(output, artifact.content());

        assertThat(artifact.artifactType()).isEqualTo(ContractPdfArtifactType.SIGNED_RECEIPT);
        assertThat(artifact.documentVersion()).isEqualTo(ContractPdfRenderer.RECEIPT_DOCUMENT_VERSION);
        try (PDDocument pdf = PDDocument.load(artifact.content())) {
            String text = new PDFTextStripper().getText(pdf).replaceAll("\\s+", " ");
            assertThat(text)
                    .contains("TRANG BIÊN NHẬN CHỮ KÝ SỐ")
                    .contains("LC-PADES-001")
                    .contains("INVESTOR-001")
                    .contains("BORROWER-001")
                    .contains("ĐÃ KÝ")
                    .contains("KHÔNG CÓ GIÁ TRỊ PHÁP LÝ")
                    .contains("NẰM NGOÀI TỆP ĐÃ KÝ")
                    .doesNotContain("UID=");
            assertThat(text.replaceAll("\\s+", "")).contains("b".repeat(64));
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
