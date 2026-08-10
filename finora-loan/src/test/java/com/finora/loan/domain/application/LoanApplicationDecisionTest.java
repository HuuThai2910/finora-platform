package com.finora.loan.domain.application;

import com.finora.loan.domain.core.FineractProductMapping;
import com.finora.loan.domain.product.LoanProduct;
import com.finora.loan.domain.product.RepaymentMethod;
import com.finora.loan.exception.LoanDomainException;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class LoanApplicationDecisionTest {

    private static final Instant NOW = Instant.parse("2026-08-08T00:00:00Z");

    @Test
    void approvesOnlyTheLatestAssessmentAndKeepsDecisionEvidence() {
        LoanApplication application = submittedApplication();
        application.startEligibility("SYSTEM", NOW.plusSeconds(1));
        application.startScoring(10L, "SYSTEM", NOW.plusSeconds(2));
        application.markPendingReview("SYSTEM", NOW.plusSeconds(3));

        assertThatThrownBy(() -> application.approveByAdmin(
                0, 9L, "POLICY_APPROVED", null, "ADMIN_MANUAL_DECISION_V1",
                "approve-key", "a".repeat(64), "ADMIN-001", NOW.plusSeconds(4)))
                .isInstanceOf(LoanDomainException.class)
                .extracting("code").isEqualTo("CREDIT_ASSESSMENT_NOT_LATEST");

        application.approveByAdmin(
                0, 10L, "POLICY_APPROVED", "Đã kiểm tra bằng chứng",
                "ADMIN_MANUAL_DECISION_V1", "approve-key", "a".repeat(64),
                "ADMIN-001", NOW.plusSeconds(4));

        assertThat(application.getStatus()).isEqualTo(LoanApplicationStatus.APPROVED);
        assertThat(application.getAdminDecisionAssessmentId()).isEqualTo(10L);
        assertThat(application.isSameAdminDecision("approve-key", "a".repeat(64))).isTrue();
    }

    @Test
    void manualEligibilityReviewCanBeRejectedWithoutAiAssessment() {
        LoanApplication application = submittedApplication();
        application.startEligibility("SYSTEM", NOW.plusSeconds(1));
        application.markEligibilityManualReview("SYSTEM", NOW.plusSeconds(2));

        application.rejectByAdmin(
                0, null, "IDENTITY_OR_KYC_NOT_ELIGIBLE", null,
                "ADMIN_MANUAL_DECISION_V1", "reject-key", "b".repeat(64),
                "ADMIN-001", NOW.plusSeconds(3));

        assertThat(application.getStatus()).isEqualTo(LoanApplicationStatus.REJECTED);
        assertThat(application.getAdminDecisionAssessmentId()).isNull();
        assertThat(application.getAdminDecidedBy()).isEqualTo("ADMIN-001");
    }

    private LoanApplication submittedApplication() {
        LoanProduct product = mock(LoanProduct.class);
        when(product.getId()).thenReturn(1L);
        when(product.getCode()).thenReturn("PERSONAL_STANDARD");
        when(product.getName()).thenReturn("Vay tiêu dùng");
        when(product.getConfigurationVersion()).thenReturn(1L);
        when(product.getMinAmount()).thenReturn(new BigDecimal("10000000.00"));
        when(product.getMaxAmount()).thenReturn(new BigDecimal("100000000.00"));
        when(product.getMinTermMonths()).thenReturn(6);
        when(product.getMaxTermMonths()).thenReturn(24);
        when(product.getAnnualInterestRate()).thenReturn(new BigDecimal("12.5000"));
        when(product.getRepaymentMethod()).thenReturn(RepaymentMethod.ANNUITY);
        FineractProductMapping mapping = mock(FineractProductMapping.class);
        when(mapping.getId()).thenReturn(2L);
        when(mapping.getFineractProductId()).thenReturn(1001L);
        when(mapping.getConfigVersion()).thenReturn("FINERACT_PRODUCT_V1");

        return LoanApplication.submit(
                "LA-0123456789ABCDEF0123", "BORROWER-001", "submit-key", "c".repeat(64),
                product, mapping, new BigDecimal("50000000"), 12, LoanPurpose.EDUCATION, null,
                ApplicantFinancialSnapshot.capture(
                        new BigDecimal("20000000"), 60, EducationLevel.UNIVERSITY,
                        HomeOwnership.RENT, new BigDecimal("3000000"), NOW),
                LocalDate.of(2026, 8, 20), "RATE_DISCLOSURE_V1", NOW);
    }
}
