package com.finora.payment.service.repayment;

import com.finora.payment.dto.response.PartialPrepaymentQuoteResponse;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Báo giá trả trước một phần gốc cho người vay: đọc dữ liệu Fineract, kiểm khoản vay không quá hạn, tính phí theo biểu
 * phí của chính hợp đồng và lưu báo giá có hạn dùng.
 */
public interface PartialPrepaymentQuoteService {

    /**
     * Lập báo giá trả trước {@code prepaidPrincipal} gốc cho khoản vay của người dùng hiện tại. Phần gốc phải nhỏ hơn
     * gốc còn lại (đóng toàn bộ thì dùng tất toán); khoản vay đang quá hạn trả
     * {@code 409 PARTIAL_PREPAYMENT_LOAN_OVERDUE}.
     */
    PartialPrepaymentQuoteResponse create(Long applicationId, BigDecimal prepaidPrincipal);

    /**
     * Báo giá trả trước một phần theo mã; báo giá của người khác trả
     * {@code 403 PARTIAL_PREPAYMENT_QUOTE_OWNER_MISMATCH}.
     */
    PartialPrepaymentQuoteResponse get(UUID quoteId);
}
