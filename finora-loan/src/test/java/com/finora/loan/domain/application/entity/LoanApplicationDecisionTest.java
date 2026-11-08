package com.finora.loan.domain.application.entity;

import com.finora.loan.domain.application.enums.EducationLevel;
import com.finora.loan.domain.application.enums.HomeOwnership;
import com.finora.loan.domain.application.enums.LoanApplicationStatus;
import com.finora.loan.domain.application.enums.LoanDecisionSource;
import com.finora.loan.domain.application.enums.LoanFundingStatus;
import com.finora.loan.domain.application.enums.LoanPurpose;
import com.finora.loan.domain.application.enums.TermsConfirmationStatus;
import com.finora.loan.domain.core.entity.FineractProductMapping;
import com.finora.loan.domain.pricing.model.RiskBasedPricingResult;
import com.finora.loan.domain.product.entity.LoanProduct;
import com.finora.loan.domain.product.enums.RepaymentMethod;
import com.finora.loan.domain.scoring.enums.AiRecommendation;
import com.finora.loan.domain.scoring.enums.BorrowerKycStatus;
import com.finora.loan.domain.scoring.enums.BorrowerProfileSource;
import com.finora.loan.domain.scoring.enums.IncomeVerificationStatus;
import com.finora.loan.exception.LoanDomainException;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

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

    @Test
    void aiPolicyApprovesAndPersistsFinalTermsWithoutChangingRequestedAmountOrTerm() {
        LoanApplication application = scoringApplication(10L);

        application.completeScoring(
                10L, AiRecommendation.APPROVED, pricing("A", "12.0000", "-0.5000"),
                99L, "AI_DECISION_V17", "SYSTEM", NOW.plusSeconds(3));

        assertThat(application.getStatus()).isEqualTo(LoanApplicationStatus.APPROVED);
        assertThat(application.getDecisionSource()).isEqualTo(LoanDecisionSource.AI_POLICY);
        assertThat(application.getFinalAnnualInterestRate()).isEqualByComparingTo("12.0000");
        assertThat(application.getFinalCalculationSnapshotId()).isEqualTo(99L);
        assertThat(application.getRequestedAmount()).isEqualByComparingTo("50000000.00");
        assertThat(application.getRequestedTermMonths()).isEqualTo(12);
    }

    @Test
    void aiPolicyKeepsMiddleScoreForAdminReviewWithFinalTermsReady() {
        LoanApplication application = scoringApplication(10L);

        application.completeScoring(
                10L, AiRecommendation.PENDING_REVIEW, pricing("C", "13.0000", "0.5000"),
                100L, "AI_DECISION_V17", "SYSTEM", NOW.plusSeconds(3));

        assertThat(application.getStatus()).isEqualTo(LoanApplicationStatus.PENDING_REVIEW);
        assertThat(application.getDecisionSource()).isNull();
        assertThat(application.getFinalAnnualInterestRate()).isEqualByComparingTo("13.0000");
        assertThat(application.getFinalCalculationSnapshotId()).isEqualTo(100L);
    }

    @Test
    void aiPolicyRejectsWithoutCreatingFinalSchedule() {
        LoanApplication application = scoringApplication(10L);

        application.completeScoring(
                10L, AiRecommendation.REJECTED, null,
                null, "AI_DECISION_V17", "SYSTEM", NOW.plusSeconds(3));

        assertThat(application.getStatus()).isEqualTo(LoanApplicationStatus.REJECTED);
        assertThat(application.getDecisionSource()).isEqualTo(LoanDecisionSource.AI_POLICY);
        assertThat(application.getFinalAnnualInterestRate()).isNull();
        assertThat(application.getFinalCalculationSnapshotId()).isNull();
    }

    @Test
    void worseningTermsRequireExplicitBorrowerAcceptanceBoundToVersionAndHash() {
        LoanApplication application = scoringApplication(10L);
        application.completeScoring(
                10L, AiRecommendation.APPROVED, pricing("C", "13.0000", "0.5000"),
                101L, "AI_DECISION_V17", "SYSTEM", NOW.plusSeconds(3));
        application.prepareTermsConfirmation(
                TermsConfirmationStatus.PENDING, "LOAN_TERMS_V1", "d".repeat(64),
                NOW.plusSeconds(3600), "SYSTEM", NOW.plusSeconds(4));

        assertThatThrownBy(() -> application.acceptTerms(
                0, "LOAN_TERMS_V1", "e".repeat(64), "accept-key", "f".repeat(64),
                "BORROWER-001", NOW.plusSeconds(5)))
                .isInstanceOf(LoanDomainException.class)
                .extracting("code").isEqualTo("TERMS_CONFIRMATION_OUTDATED");

        application.acceptTerms(
                0, "LOAN_TERMS_V1", "d".repeat(64), "accept-key", "f".repeat(64),
                "BORROWER-001", NOW.plusSeconds(5));

        assertThat(application.getTermsConfirmationStatus()).isEqualTo(TermsConfirmationStatus.ACCEPTED);
        assertThat(application.getTermsRespondedBy()).isEqualTo("BORROWER-001");
        assertThat(application.isSameTermsConsent("accept-key", "f".repeat(64))).isTrue();
    }

    @Test
    void nonWorseningTermsCanRecordSubmissionAuthorizationWithoutSecondPrompt() {
        LoanApplication application = scoringApplication(10L);
        application.completeScoring(
                10L, AiRecommendation.APPROVED, pricing("A", "12.0000", "-0.5000"),
                102L, "AI_DECISION_V17", "SYSTEM", NOW.plusSeconds(3));

        application.prepareTermsConfirmation(
                TermsConfirmationStatus.AUTO_AUTHORIZED, "LOAN_TERMS_V1", "a".repeat(64),
                NOW.plusSeconds(3600), "SYSTEM", NOW.plusSeconds(4));

        assertThat(application.getTermsConfirmationStatus()).isEqualTo(TermsConfirmationStatus.AUTO_AUTHORIZED);
        assertThat(application.getTermsRespondedBy()).isEqualTo("BORROWER-001");
        assertThat(application.getTermsRespondedAt()).isEqualTo(NOW);
    }

    @Test
    void borrowerCanDeclinePendingTermsAndEvidenceIsKept() {
        LoanApplication application = scoringApplication(10L);
        application.completeScoring(
                10L, AiRecommendation.APPROVED, pricing("C", "13.0000", "0.5000"),
                103L, "AI_DECISION_V17", "SYSTEM", NOW.plusSeconds(3));
        application.prepareTermsConfirmation(
                TermsConfirmationStatus.PENDING, "LOAN_TERMS_V1", "1".repeat(64),
                NOW.plusSeconds(3600), "SYSTEM", NOW.plusSeconds(4));

        application.declineTerms(
                0, "LOAN_TERMS_V1", "1".repeat(64), "TERMS_NOT_ACCEPTED",
                "Khoản trả mới chưa phù hợp", "decline-key", "2".repeat(64),
                "BORROWER-001", NOW.plusSeconds(5));

        assertThat(application.getTermsConfirmationStatus()).isEqualTo(TermsConfirmationStatus.DECLINED);
        assertThat(application.getTermsDeclineReasonCode()).isEqualTo("TERMS_NOT_ACCEPTED");
        assertThat(application.getTermsDeclineReasonDetail()).isEqualTo("Khoản trả mới chưa phù hợp");
        assertThat(application.isSameTermsConsent("decline-key", "2".repeat(64))).isTrue();
    }

    @Test
    void unansweredPendingTermsExpireWithoutInventingBorrowerConsent() {
        LoanApplication application = scoringApplication(10L);
        application.completeScoring(
                10L, AiRecommendation.APPROVED, pricing("C", "13.0000", "0.5000"),
                104L, "AI_DECISION_V17", "SYSTEM", NOW.plusSeconds(3));
        application.prepareTermsConfirmation(
                TermsConfirmationStatus.PENDING, "LOAN_TERMS_V1", "3".repeat(64),
                NOW.plusSeconds(10), "SYSTEM", NOW.plusSeconds(4));

        assertThat(application.expireTerms("LOAN_TERMS_EXPIRY_WORKER", NOW.plusSeconds(11))).isTrue();
        assertThat(application.getTermsConfirmationStatus()).isEqualTo(TermsConfirmationStatus.EXPIRED);
        assertThat(application.getTermsRespondedBy()).isNull();
        assertThat(application.getTermsRespondedAt()).isNull();
    }

    @Test
    void fullyFundedSnapshotIsIdempotentButCannotBeSilentlyReplaced() {
        LoanApplication application = scoringApplication(10L);
        application.completeScoring(
                10L, AiRecommendation.APPROVED, pricing("A", "12.0000", "-0.5000"),
                105L, "AI_DECISION_V17", "SYSTEM", NOW.plusSeconds(3));
        application.prepareTermsConfirmation(
                TermsConfirmationStatus.AUTO_AUTHORIZED, "LOAN_TERMS_V1", "a".repeat(64),
                NOW.plusSeconds(3600), "SYSTEM", NOW.plusSeconds(4));
        assertThat(application.requestFunding(1, 1, "SYSTEM", NOW.plusSeconds(5))).isTrue();

        Instant completedAt = NOW.plusSeconds(6);
        assertThat(application.markFullyFunded(
                20L, new BigDecimal("50000000.00"), 1L,
                "b".repeat(64), "[{\"commitmentId\":1}]", completedAt,
                "SYSTEM", NOW.plusSeconds(7))).isTrue();
        assertThat(application.markFullyFunded(
                20L, new BigDecimal("50000000.00"), 1L,
                "b".repeat(64), "[{\"commitmentId\":1}]", completedAt,
                "SYSTEM", NOW.plusSeconds(8))).isFalse();

        assertThatThrownBy(() -> application.markFullyFunded(
                20L, new BigDecimal("50000000.00"), 2L,
                "c".repeat(64), "[{\"commitmentId\":2}]", completedAt,
                "SYSTEM", NOW.plusSeconds(9)))
                .isInstanceOf(LoanDomainException.class)
                .extracting("code").isEqualTo("CONFLICTING_FULLY_FUNDED_EVENT");
    }

    @Test
    void investorWithdrawalReopensFundingAndOnlyNewerAllocationCompletesIt() {
        LoanApplication application = scoringApplication(10L);
        application.completeScoring(
                10L, AiRecommendation.APPROVED, pricing("A", "12.0000", "-0.5000"),
                105L, "AI_DECISION_V17", "SYSTEM", NOW.plusSeconds(3));
        application.prepareTermsConfirmation(
                TermsConfirmationStatus.AUTO_AUTHORIZED, "LOAN_TERMS_V1", "a".repeat(64),
                NOW.plusSeconds(3600), "SYSTEM", NOW.plusSeconds(4));
        application.requestFunding(1, 1, "SYSTEM", NOW.plusSeconds(5));
        assertThatThrownBy(() -> application.reopenFunding("SYSTEM", NOW.plusSeconds(6)))
                .isInstanceOf(LoanDomainException.class)
                .extracting("code").isEqualTo("LOAN_FUNDING_NOT_FULLY_FUNDED");
        application.markFullyFunded(20L, new BigDecimal("50000000.00"), 1L, "b".repeat(64),
                "[{\"commitmentId\":1}]", NOW.plusSeconds(6), "SYSTEM", NOW.plusSeconds(7));

        application.reopenFunding("SYSTEM", NOW.plusSeconds(8));

        // Bản allocation đã khóa được giữ để Investment đối chiếu; allocation kế tiếp phải mới hơn.
        assertThat(application.getFundingStatus()).isEqualTo(LoanFundingStatus.REQUESTED);
        assertThat(application.getAllocationVersion()).isEqualTo(1L);
        assertThatThrownBy(() -> application.markFullyFunded(20L, new BigDecimal("50000000.00"), 1L,
                "c".repeat(64), "[{\"commitmentId\":2}]", NOW.plusSeconds(9), "SYSTEM", NOW.plusSeconds(9)))
                .isInstanceOf(LoanDomainException.class)
                .extracting("code").isEqualTo("STALE_FUNDING_ALLOCATION");
        assertThat(application.markFullyFunded(20L, new BigDecimal("50000000.00"), 2L, "c".repeat(64),
                "[{\"commitmentId\":2}]", NOW.plusSeconds(10), "SYSTEM", NOW.plusSeconds(10))).isTrue();
        assertThat(application.getFundingStatus()).isEqualTo(LoanFundingStatus.FULLY_FUNDED);
        assertThat(application.getAllocationVersion()).isEqualTo(2L);
    }

    @Test
    void fundingExpiryClosesRequestedRoundOnceAndNeverOverridesFullyFunded() {
        LoanApplication application = scoringApplication(10L);
        application.completeScoring(
                10L, AiRecommendation.APPROVED, pricing("A", "12.0000", "-0.5000"),
                105L, "AI_DECISION_V17", "SYSTEM", NOW.plusSeconds(3));
        application.prepareTermsConfirmation(
                TermsConfirmationStatus.AUTO_AUTHORIZED, "LOAN_TERMS_V1", "a".repeat(64),
                NOW.plusSeconds(3600), "SYSTEM", NOW.plusSeconds(4));
        assertThat(application.requestFunding(1, 1, "SYSTEM", NOW.plusSeconds(5))).isTrue();

        assertThatThrownBy(() -> application.markFundingExpired(20L, 2, NOW.plusSeconds(6), "SYSTEM", NOW))
                .isInstanceOf(LoanDomainException.class)
                .extracting("code").isEqualTo("FUNDING_ROUND_MISMATCH");

        assertThat(application.markFundingExpired(20L, 1, NOW.plusSeconds(6), "SYSTEM", NOW.plusSeconds(7)))
                .isTrue();
        assertThat(application.getFundingStatus()).isEqualTo(LoanFundingStatus.EXPIRED);
        assertThat(application.getFundingExpiredAt()).isEqualTo(NOW.plusSeconds(6));
        assertThat(application.markFundingExpired(20L, 1, NOW.plusSeconds(6), "SYSTEM", NOW.plusSeconds(8)))
                .isFalse();
        // Đợt đã hết hạn thì kết quả đủ vốn đến muộn cũng không được lập Contract.
        assertThatThrownBy(() -> application.markFullyFunded(
                20L, new BigDecimal("50000000.00"), 1L, "b".repeat(64), "[{\"commitmentId\":1}]",
                NOW.plusSeconds(9), "SYSTEM", NOW.plusSeconds(9)))
                .isInstanceOf(LoanDomainException.class)
                .extracting("code").isEqualTo("LOAN_FUNDING_NOT_REQUESTED");
    }

    @Test
    void fundingCancellationClosesOpenOrFullyFundedRoundOnce() {
        LoanApplication application = scoringApplication(10L);
        application.completeScoring(
                10L, AiRecommendation.APPROVED, pricing("A", "12.0000", "-0.5000"),
                105L, "AI_DECISION_V17", "SYSTEM", NOW.plusSeconds(3));
        application.prepareTermsConfirmation(
                TermsConfirmationStatus.AUTO_AUTHORIZED, "LOAN_TERMS_V1", "a".repeat(64),
                NOW.plusSeconds(3600), "SYSTEM", NOW.plusSeconds(4));
        application.requestFunding(1, 1, "SYSTEM", NOW.plusSeconds(5));

        application.validateRequestedFundingWithdrawal(0, "BORROWER-001");
        assertThat(application.markFundingCancelled("BORROWER_WITHDREW", "SYSTEM", NOW.plusSeconds(6))).isTrue();
        assertThat(application.getFundingStatus()).isEqualTo(LoanFundingStatus.CANCELLED);
        assertThat(application.getAllocationHash()).isNull();

        LoanApplication fundedApplication = scoringApplication(11L);
        fundedApplication.completeScoring(
                11L, AiRecommendation.APPROVED, pricing("A", "12.0000", "-0.5000"),
                106L, "AI_DECISION_V17", "SYSTEM", NOW.plusSeconds(3));
        fundedApplication.prepareTermsConfirmation(
                TermsConfirmationStatus.AUTO_AUTHORIZED, "LOAN_TERMS_V1", "a".repeat(64),
                NOW.plusSeconds(3600), "SYSTEM", NOW.plusSeconds(4));
        fundedApplication.requestFunding(1, 1, "SYSTEM", NOW.plusSeconds(5));
        fundedApplication.markFullyFunded(20L, new BigDecimal("50000000.00"), 1L, "b".repeat(64),
                "[{\"commitmentId\":1}]", NOW.plusSeconds(6), "SYSTEM", NOW.plusSeconds(6));
        assertThat(fundedApplication.markFundingCancelled("CONTRACT_EXPIRED", "SYSTEM", NOW.plusSeconds(7))).isTrue();
        assertThat(fundedApplication.getFundingStatus()).isEqualTo(LoanFundingStatus.CANCELLED);
        assertThat(fundedApplication.getFundingCancelledAt()).isEqualTo(NOW.plusSeconds(7));
        assertThat(fundedApplication.getFundingCancelReason()).isEqualTo("CONTRACT_EXPIRED");
        // Hai nguồn (hết hạn ký và giải ngân lỗi) gọi lại không được phát bồi hoàn lần hai.
        assertThat(fundedApplication.markFundingCancelled("DISBURSEMENT_FAILED", "SYSTEM", NOW.plusSeconds(8))).isFalse();
        assertThat(fundedApplication.getFundingCancelReason()).isEqualTo("CONTRACT_EXPIRED");
    }

    @Test
    void cancellationReasonMustMatchFundingStage() {
        LoanApplication requested = approvedFundingApplication(12L);
        requested.requestFunding(1, 1, "SYSTEM", NOW.plusSeconds(5));
        assertThatThrownBy(() -> requested.markFundingCancelled(
                "CONTRACT_DECLINED", "SYSTEM", NOW.plusSeconds(6)))
                .hasMessageContaining("Lý do hủy không phù hợp");

        LoanApplication funded = approvedFundingApplication(13L);
        funded.requestFunding(1, 1, "SYSTEM", NOW.plusSeconds(5));
        funded.markFullyFunded(21L, new BigDecimal("50000000.00"), 1L, "a".repeat(64),
                "[{\"commitmentId\":1}]", NOW.plusSeconds(6), "SYSTEM", NOW.plusSeconds(6));
        assertThatThrownBy(() -> funded.markFundingCancelled(
                "BORROWER_WITHDREW", "SYSTEM", NOW.plusSeconds(7)))
                .hasMessageContaining("Lý do hủy không phù hợp");
    }

    @Test
    void borrowerCanWithdrawAtEveryPreDecisionStage() {
        LoanApplication scoring = scoringApplication(10L);
        scoring.withdraw(0, "Không còn nhu cầu", "BORROWER-001", NOW.plusSeconds(3));
        assertThat(scoring.getStatus()).isEqualTo(LoanApplicationStatus.WITHDRAWN);

        LoanApplication review = scoringApplication(11L);
        review.markPendingReview("SYSTEM", NOW.plusSeconds(3));
        review.withdraw(0, null, "BORROWER-001", NOW.plusSeconds(4));
        assertThat(review.getStatus()).isEqualTo(LoanApplicationStatus.WITHDRAWN);
    }

    @Test
    void fullyFundedApplicationCannotExpire() {
        LoanApplication application = scoringApplication(10L);
        application.completeScoring(
                10L, AiRecommendation.APPROVED, pricing("A", "12.0000", "-0.5000"),
                105L, "AI_DECISION_V17", "SYSTEM", NOW.plusSeconds(3));
        application.prepareTermsConfirmation(
                TermsConfirmationStatus.AUTO_AUTHORIZED, "LOAN_TERMS_V1", "a".repeat(64),
                NOW.plusSeconds(3600), "SYSTEM", NOW.plusSeconds(4));
        application.requestFunding(1, 1, "SYSTEM", NOW.plusSeconds(5));
        application.markFullyFunded(20L, new BigDecimal("50000000.00"), 1L, "b".repeat(64),
                "[{\"commitmentId\":1}]", NOW.plusSeconds(6), "SYSTEM", NOW.plusSeconds(6));

        assertThatThrownBy(() -> application.markFundingExpired(20L, 1, NOW.plusSeconds(7), "SYSTEM", NOW))
                .isInstanceOf(LoanDomainException.class)
                .extracting("code").isEqualTo("LOAN_FUNDING_NOT_REQUESTED");
    }

    private LoanApplication scoringApplication(Long assessmentId) {
        LoanApplication application = submittedApplication();
        application.startEligibility("SYSTEM", NOW.plusSeconds(1));
        application.startScoring(assessmentId, "SYSTEM", NOW.plusSeconds(2));
        return application;
    }

    private LoanApplication approvedFundingApplication(Long assessmentId) {
        LoanApplication application = scoringApplication(assessmentId);
        application.completeScoring(
                assessmentId, AiRecommendation.APPROVED, pricing("A", "12.0000", "-0.5000"),
                100L + assessmentId, "AI_DECISION_V17", "SYSTEM", NOW.plusSeconds(3));
        application.prepareTermsConfirmation(
                TermsConfirmationStatus.AUTO_AUTHORIZED, "LOAN_TERMS_V1", "a".repeat(64),
                NOW.plusSeconds(3600), "SYSTEM", NOW.plusSeconds(4));
        return application;
    }

    private RiskBasedPricingResult pricing(String grade, String finalRate, String adjustment) {
        return new RiskBasedPricingResult(
                grade,
                new BigDecimal(adjustment),
                new BigDecimal(adjustment),
                new BigDecimal(finalRate),
                "RISK_GRADE_RATE_V1"
        );
    }

    @Test
    void kycSnapshotMustBeVerifiedAndIsCapturedOnlyOnce() {
        LoanApplication application = submittedApplication();

        assertThatThrownBy(() -> application.attachKycSnapshot(kyc(BorrowerKycStatus.PENDING)))
                .isInstanceOf(LoanDomainException.class)
                .extracting("code").isEqualTo("BORROWER_KYC_REQUIRED");

        application.attachKycSnapshot(kyc(BorrowerKycStatus.VERIFIED));
        assertThat(application.getKycSnapshot().getKycStatus()).isEqualTo(BorrowerKycStatus.VERIFIED);
        assertThat(application.getKycSnapshot().getSource()).isEqualTo(BorrowerProfileSource.USER_SERVICE);
        assertThatThrownBy(() -> application.attachKycSnapshot(kyc(BorrowerKycStatus.VERIFIED)))
                .isInstanceOf(IllegalStateException.class);
    }

    private static BorrowerKycSnapshot kyc(BorrowerKycStatus status) {
        return BorrowerKycSnapshot.capture(status, 27, "USER-KYC-BORROWER-001", "USER_KYC_SUMMARY_V1",
                IncomeVerificationStatus.NOT_VERIFIED, BorrowerProfileSource.USER_SERVICE, NOW);
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
        when(product.getMinAnnualInterestRate()).thenReturn(new BigDecimal("8.0000"));
        when(product.getAnnualInterestRate()).thenReturn(new BigDecimal("12.5000"));
        when(product.getMaxAnnualInterestRate()).thenReturn(new BigDecimal("20.0000"));
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
