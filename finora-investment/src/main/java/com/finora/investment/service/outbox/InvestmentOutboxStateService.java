package com.finora.investment.service.outbox;

import java.util.List;
import java.util.UUID;

/**
 * Trạng thái event outbox: lấy event đến hạn, nhận event (khoá + lease), ghi đã gửi hoặc lỗi.
 */
public interface InvestmentOutboxStateService {

    List<Long> dueIds();

    /** Nhận event để gửi; {@code null} nếu đã có relay khác giữ lease, đã gửi hoặc đã hết lượt thử. */
    ClaimedInvestmentOutboxMessage claim(Long id);

    void markPublished(Long id, UUID token);

    void markFailed(Long id, UUID token, String code, String detail, boolean retryable);
}
