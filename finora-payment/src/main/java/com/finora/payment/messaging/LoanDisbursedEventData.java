package com.finora.payment.messaging;

import java.time.Instant;
import java.util.UUID;

/**
 * {@code LoanDisbursed.v1}. {@code earlySettlementPolicyVersion} là field optional (G-POLICY-W2): biểu phí trả trước
 * hợp đồng đã công bố; {@code null} khi hợp đồng không có điều khoản phí hoặc event từ Loan bản cũ.
 */
public record LoanDisbursedEventData(UUID sagaId, Long loanApplicationId, String applicationNumber,
        String contractNumber, Long listingId, String amount, String currency,
        String paymentReference, Long fineractLoanId, String coreConfigVersion, Instant disbursedAt,
        String earlySettlementPolicyVersion) {

    public LoanDisbursedEventData(UUID sagaId, Long loanApplicationId, String applicationNumber,
            String contractNumber, Long listingId, String amount, String currency,
            String paymentReference, Long fineractLoanId, String coreConfigVersion, Instant disbursedAt) {
        this(sagaId, loanApplicationId, applicationNumber, contractNumber, listingId, amount, currency,
                paymentReference, fineractLoanId, coreConfigVersion, disbursedAt, null);
    }
}
