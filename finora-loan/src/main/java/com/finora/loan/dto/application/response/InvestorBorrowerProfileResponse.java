package com.finora.loan.dto.application.response;

import com.fasterxml.jackson.databind.JsonNode;
import com.finora.loan.domain.application.CreditInformationSource;
import com.finora.loan.domain.application.EducationLevel;
import com.finora.loan.domain.application.HomeOwnership;
import com.finora.loan.domain.application.LoanDecisionSource;
import com.finora.loan.domain.application.LoanPurpose;
import com.finora.loan.domain.product.RepaymentMethod;
import com.finora.loan.domain.scoring.BorrowerKycStatus;
import com.finora.loan.domain.scoring.BorrowerProfileSource;
import com.finora.loan.domain.scoring.CreditProfileSource;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/**
 * Hồ sơ người vay nhà đầu tư xem trước khi góp vốn: những gì admin thấy ở trang thẩm định, trừ định
 * danh và trừ giải thích SHAP.
 *
 * <p>Cố ý không có {@code borrowerId}, họ tên, CCCD, liên hệ, địa chỉ (NĐ 94/2025 Điều 11 khoản 2
 * điểm d: bảo mật với bên không liên quan; Luật BVDLCN 2025: tối thiểu hóa). Cũng không có
 * {@code modelExplanation} (SHAP) và {@code borrowerExplanation}: SHAP chỉ dành cho thẩm định viên,
 * còn thông điệp gửi người vay viết cho chính người vay và phần gợi ý xếp theo SHAP.</p>
 *
 * <p>Tiền và tỷ lệ là số JSON như mọi DTO khác của Loan; {@code dtiSnapshot} đã là phần trăm,
 * {@code pdProbability} là tỷ lệ 0..1.</p>
 */
public record InvestorBorrowerProfileResponse(
        String applicationNumber,
        LoanRequest loan,
        RepaymentCapacity capacity,
        Background background,
        /** {@code null} khi Loan chưa có bản ghi lịch sử vay nội bộ của người vay. */
        CreditHistory creditHistory,
        /** {@code null} khi lần chấm gần nhất chưa thành công. */
        CreditAssessment assessment,
        List<RuleResult> ruleResults
) {
    /** Khoản vay người vay đề nghị; kỳ trả lấy từ lịch cuối, chưa có thì lịch lúc nộp. */
    public record LoanRequest(
            LoanPurpose purposeCode,
            String purposeLabel,
            String purposeDetail,
            BigDecimal requestedAmount,
            Integer requestedTermMonths,
            RepaymentMethod repaymentMethod,
            BigDecimal finalAnnualInterestRate,
            BigDecimal firstInstallment,
            BigDecimal maximumInstallment,
            BigDecimal totalRepayment,
            LocalDate expectedDisbursementDate
    ) {
    }

    /** Số liệu người vay tự khai lúc nộp hồ sơ. */
    public record RepaymentCapacity(
            BigDecimal declaredMonthlyIncome,
            BigDecimal monthlyDebtObligations,
            BigDecimal dtiSnapshot,
            Integer employmentLengthMonths,
            CreditInformationSource informationSource,
            Instant capturedAt
    ) {
    }

    /**
     * Nhân thân không định danh. Tuổi và eKYC lấy từ lần kiểm tra điều kiện vay; {@code profileSource}
     * cho biết đó là hồ sơ thật từ User Service hay hồ sơ giả lập của môi trường thử nghiệm.
     */
    public record Background(
            Integer age,
            BorrowerKycStatus kycStatus,
            BorrowerProfileSource profileSource,
            Instant checkedAt,
            HomeOwnership homeOwnership,
            EducationLevel educationLevel
    ) {
    }

    /** Lịch sử vay tại FINORA (không phải CIC). */
    public record CreditHistory(
            boolean hasInternalCreditHistory,
            int completedLoanCount,
            int internalDelinquenciesLast2Years,
            int internalDefaultedLoanCount,
            CreditProfileSource source
    ) {
    }

    /** Kết quả chấm điểm; {@code riskScore} là điểm theo bảng luật 0..100. */
    public record CreditAssessment(
            BigDecimal evaluationScore,
            String creditGrade,
            BigDecimal pdProbability,
            Integer riskScore,
            LoanDecisionSource decisionSource,
            Instant scoredAt
    ) {
    }

    /** Một luật đã chấm, chép theo allowlist từ {@code rule_trace} của AI; {@code value} là số, chuỗi hoặc null. */
    public record RuleResult(
            String code,
            String description,
            String field,
            JsonNode value,
            Integer points,
            Integer maxPoints,
            BigDecimal weight,
            boolean missingData
    ) {
    }
}
