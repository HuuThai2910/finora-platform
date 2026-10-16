package com.finora.payment.service.repayment;

import com.finora.common.exception.BusinessException;
import java.math.BigDecimal;
import java.util.Map;
import org.springframework.http.HttpStatus;

/**
 * Các biểu phí trả trước/tất toán đã công bố trong hợp đồng (G-POLICY-W2, B17).
 *
 * <p>Mỗi phiên bản bất biến và nằm trong code, không trong biến môi trường: hợp đồng đã ký trích đúng tên phiên bản, nên
 * đổi một tỷ lệ qua cấu hình sẽ làm khoản vay cũ bị tính khác điều đã ký. Đổi biểu phí là thêm phiên bản mới ở đây,
 * triển khai Payment trước khi Loan in phiên bản đó vào hợp đồng.</p>
 */
public final class EarlySettlementPolicyCatalog {

    /** Biểu phí in trong hợp đồng {@code LOAN_TERMS_V2} (điều 4, {@code LoanServicingContractTerms} của Loan). */
    public static final String AGRIBANK_REFERENCE_FINORA_2026_V2 = "AGRIBANK_REFERENCE_FINORA_2026_V2";

    /**
     * Hợp đồng không có điều khoản phí trả trước ({@code LOAN_TERMS_V1}, trước 2026-10-16) hoặc tài khoản mở trước khi
     * Payment lưu phiên bản: không thu phí, vì phí trả nợ trước hạn phải được ghi rõ trong hợp đồng
     * ({@code LEGAL-SERVICING-01}).
     */
    public static final String NO_FEE_DISCLOSED = "NO_FEE_DISCLOSED";

    private static final BigDecimal ZERO = BigDecimal.ZERO;

    private static final Map<String, EarlySettlementPolicy> POLICIES = Map.of(
            AGRIBANK_REFERENCE_FINORA_2026_V2, new EarlySettlementPolicy(AGRIBANK_REFERENCE_FINORA_2026_V2,
                    new BigDecimal("0.01"), new BigDecimal("0.005"), new BigDecimal("0.50"), new BigDecimal("0.70"),
                    new BigDecimal("0.02"), new BigDecimal("0.015"), new BigDecimal("100000.00"),
                    new BigDecimal("0.80")),
            NO_FEE_DISCLOSED, new EarlySettlementPolicy(NO_FEE_DISCLOSED,
                    ZERO, ZERO, new BigDecimal("0.50"), new BigDecimal("0.70"), ZERO, ZERO,
                    ZERO.setScale(2), new BigDecimal("0.80")));

    private EarlySettlementPolicyCatalog() {
    }

    /**
     * Biểu phí của một khoản vay theo phiên bản lưu trên tài khoản thu nợ. Phiên bản lạ nghĩa là Loan đã ký theo biểu phí
     * Payment chưa biết: dừng báo giá thay vì tính theo biểu phí khác.
     */
    public static EarlySettlementPolicy forAccount(String accountPolicyVersion) {
        String version = accountPolicyVersion == null || accountPolicyVersion.isBlank()
                ? NO_FEE_DISCLOSED : accountPolicyVersion.trim();
        EarlySettlementPolicy policy = POLICIES.get(version);
        if (policy == null) {
            throw new BusinessException(HttpStatus.CONFLICT, "EARLY_SETTLEMENT_POLICY_UNKNOWN",
                    "Chưa hỗ trợ biểu phí trả trước " + version + " của hợp đồng này; vui lòng liên hệ FINORA");
        }
        return policy;
    }

    /**
     * Tham số một biểu phí. Khoản vay đến 12 tháng tính theo tỷ lệ thời gian đã trôi qua; trên 12 tháng tính theo năm.
     * Phí khác 0 luôn ít nhất {@code minimumFee}; phần nhà đầu tư là {@code investorShareRate} của tổng phí.
     */
    public record EarlySettlementPolicy(
            String version,
            BigDecimal shortTermFirstBandRate,
            BigDecimal shortTermSecondBandRate,
            BigDecimal shortTermFirstBandEnd,
            BigDecimal shortTermFreeAfter,
            BigDecimal mediumTermYearOneRate,
            BigDecimal mediumTermYearTwoRate,
            BigDecimal minimumFee,
            BigDecimal investorShareRate
    ) {
    }
}
