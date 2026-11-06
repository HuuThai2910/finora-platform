package com.finora.payment.service.repayment;

import com.finora.payment.domain.servicing.entity.PaymentNoteOwnership;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * Chia một khoản tiền cho các Note theo tỷ lệ dư nợ gốc còn lại của từng Note.
 */
public interface RepaymentAllocator {

    /**
     * Phần tiền của từng Note (khóa là {@code noteId}, theo thứ tự danh sách Note). Mỗi phần làm tròn xuống tới 0,01;
     * phần lẻ còn lại chia lần lượt từng 0,01 theo thứ tự phần dư giảm dần nên tổng luôn đúng bằng {@code amount}.
     */
    Map<Long, BigDecimal> allocate(BigDecimal amount, List<PaymentNoteOwnership> notes);
}
