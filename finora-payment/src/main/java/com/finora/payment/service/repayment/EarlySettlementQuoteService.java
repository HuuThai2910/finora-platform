package com.finora.payment.service.repayment;

import com.finora.payment.dto.response.EarlySettlementQuoteResponse;

import java.util.UUID;

/**
 * Báo giá tất toán khoản vay cho người vay: đọc báo giá Fineract, tính phí theo biểu phí của chính hợp đồng và lưu báo
 * giá có hạn dùng để lệnh tất toán thu đúng con số đã báo.
 */
public interface EarlySettlementQuoteService {

    /**
     * Lập báo giá tất toán cho khoản vay của người dùng hiện tại; chỉ khoản vay core V4 còn hoạt động và không còn
     * khoản đã thu đang chờ ghi Fineract.
     */
    EarlySettlementQuoteResponse create(Long loanApplicationId);

    /** Báo giá tất toán theo mã; báo giá của người khác trả {@code 403 EARLY_SETTLEMENT_QUOTE_OWNER_MISMATCH}. */
    EarlySettlementQuoteResponse get(UUID quoteId);
}
