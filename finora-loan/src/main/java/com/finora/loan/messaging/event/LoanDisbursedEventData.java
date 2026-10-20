package com.finora.loan.messaging.event;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * {@code LoanDisbursed.v1}. Các field optional thêm sau, consumer cũ bỏ qua:
 * {@code earlySettlementPolicyVersion} (G-POLICY-W2) là biểu phí trả trước hợp đồng đã công bố, {@code null} với hợp đồng
 * không có điều khoản phí; {@code loanNumber} và {@code maturityDate} (B34) là mã khoản vay servicing và ngày đáo hạn trên
 * core theo ngày giải ngân thực tế, {@code maturityDate} có thể {@code null}; {@code borrowerId} (P2, W3F) là ID logic của
 * người vay (claim {@code user_id}).
 *
 * @param nextDueDate   kỳ đầu tiên phải trả, đọc cùng lần đọc Fineract với {@code maturityDate} (P3, D76); vắng mặt khi core
 *                      không trả lịch. FINORA không tự tính lịch
 * @param nextDueAmount số tiền của kỳ đó, chuỗi decimal VND hai chữ số thập phân (Blockchain neo hash nên không dùng số);
 *                      vắng mặt khi core không trả
 */
public record LoanDisbursedEventData(UUID sagaId, Long loanApplicationId, String applicationNumber,
        String contractNumber, Long listingId, String amount, String currency,
        String paymentReference, Long fineractLoanId, String coreConfigVersion, Instant disbursedAt,
        String earlySettlementPolicyVersion, String loanNumber, LocalDate maturityDate, String borrowerId,
        @JsonInclude(JsonInclude.Include.NON_NULL) LocalDate nextDueDate,
        @JsonInclude(JsonInclude.Include.NON_NULL) String nextDueAmount) {

    public LoanDisbursedEventData(UUID sagaId, Long loanApplicationId, String applicationNumber,
            String contractNumber, Long listingId, String amount, String currency,
            String paymentReference, Long fineractLoanId, String coreConfigVersion, Instant disbursedAt,
            String earlySettlementPolicyVersion) {
        this(sagaId, loanApplicationId, applicationNumber, contractNumber, listingId, amount, currency,
                paymentReference, fineractLoanId, coreConfigVersion, disbursedAt, earlySettlementPolicyVersion, null, null,
                null, null, null);
    }
}
