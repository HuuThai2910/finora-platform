package com.finora.loan.dto.application.response;

import com.finora.loan.domain.application.enums.CreditInformationSource;
import com.finora.loan.domain.application.enums.EducationLevel;
import com.finora.loan.domain.application.enums.HomeOwnership;
import com.finora.loan.domain.application.enums.LoanDecisionSource;
import com.finora.loan.domain.application.enums.LoanPurpose;
import com.finora.loan.domain.product.enums.RepaymentMethod;
import com.finora.loan.domain.scoring.enums.BorrowerKycStatus;
import com.finora.loan.domain.scoring.enums.BorrowerProfileSource;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

/**
 * Hồ sơ người vay nhà đầu tư xem trước khi góp vốn: những gì admin thấy ở trang thẩm định, trừ định
 * danh và trừ giải thích SHAP.
 *
 * <p>Cố ý không có {@code borrowerId}, họ tên, CCCD, liên hệ, địa chỉ (NĐ 94/2025 Điều 11 khoản 2
 * điểm d: bảo mật với bên không liên quan; Luật BVDLCN 2025: tối thiểu hóa). Cũng không có
 * {@code modelExplanation} (SHAP) và {@code borrowerExplanation}: SHAP chỉ dành cho thẩm định viên,
 * còn thông điệp gửi người vay viết cho chính người vay và phần gợi ý xếp theo SHAP. Lịch sử tín dụng
 * (lịch sử vay tại FINORA) và bảng luật đã chấm (gồm điểm CIC) cũng không công bố cho nhà đầu tư;
 * nhà đầu tư chỉ thấy điểm tổng hợp trong {@code assessment}.</p>
 *
 * <p>Tiền và tỷ lệ là số JSON như mọi DTO khác của Loan; {@code dtiSnapshot} đã là phần trăm,
 * {@code pdProbability} là tỷ lệ 0..1.</p>
 */
public record InvestorBorrowerProfileResponse(
        String applicationNumber,
        LoanRequest loan,
        RepaymentCapacity capacity,
        Background background,
        /** {@code null} khi lần chấm gần nhất chưa thành công. */
        CreditAssessment assessment
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
}
