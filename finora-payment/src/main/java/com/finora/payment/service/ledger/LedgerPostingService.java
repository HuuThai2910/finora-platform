package com.finora.payment.service.ledger;

/**
 * Sổ cái kép của Payment: post transaction cân bằng theo idempotency key; số dư ví, transaction và entries commit hoặc
 * rollback cùng nhau.
 */
public interface LedgerPostingService {

    /**
     * Post một transaction cân bằng (tổng debit bằng tổng credit) và cập nhật số dư các ví có trong entries. Gọi lại
     * cùng idempotency key và cùng nội dung trả kết quả cũ với {@code replayed = true}; cùng key khác nội dung thì
     * {@code 409 PAYMENT_IDEMPOTENCY_CONFLICT}.
     */
    LedgerPostingResult post(LedgerPostingCommand command);
}
