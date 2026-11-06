package com.finora.payment.service.repayment;

import com.finora.payment.integration.fineract.contract.EarlySettlementCoreQuote;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Tính phí trả trước/tất toán theo đúng biểu phí hợp đồng của khoản vay đã công bố (G-POLICY-W2).
 */
public interface EarlySettlementFeeCalculator {

    /** Phí tất toán theo báo giá Fineract: phần gốc trả trước là phần gốc trong báo giá. */
    Fee calculate(String accountPolicyVersion, EarlySettlementCoreQuote quote);

    /**
     * Phí trả trước {@code prepaidPrincipal} theo phiên bản biểu phí lưu trên tài khoản thu nợ. Thời hạn ngoài 1–24
     * tháng bị từ chối; phiên bản biểu phí Payment chưa biết trả {@code 409 EARLY_SETTLEMENT_POLICY_UNKNOWN}.
     */
    Fee calculate(String accountPolicyVersion, BigDecimal prepaidPrincipal, int term, LocalDate disbursedDate,
            LocalDate maturityDate, LocalDate transactionDate);

    /** Snapshot cả tỷ lệ lẫn cách chia để worker không bị ảnh hưởng khi thêm phiên bản biểu phí mới. */
    record Fee(BigDecimal rate, BigDecimal amount, BigDecimal investorShare,
            BigDecimal finoraShare, String policyVersion) {}
}
