package com.finora.loan.messaging.event;

import java.time.Instant;
import java.util.UUID;

/**
 * {@code LoanDisbursed.v1}. {@code earlySettlementPolicyVersion} là field optional thêm cho G-POLICY-W2: biểu phí trả
 * trước hợp đồng đã công bố, {@code null} với hợp đồng không có điều khoản phí; consumer cũ bỏ qua field này.
 */
public record LoanDisbursedEventData(UUID sagaId, Long loanApplicationId, String applicationNumber,
        String contractNumber, Long listingId, String amount, String currency,
        String paymentReference, Long fineractLoanId, String coreConfigVersion, Instant disbursedAt,
        String earlySettlementPolicyVersion) {}

