package com.finora.loan.service.servicing;

import java.util.List;

/**
 * Đồng bộ servicing projection của khoản vay từ Fineract, dùng chung cho worker và màn đối soát.
 */
public interface LoanServicingSyncService {

    /** ID khoản vay ACTIVE/DEFAULTED có projection cũ nhất, tối đa {@code batchSize} (không quá 100). */
    List<Long> dueIds(int batchSize);

    /** Đồng bộ một khoản vay; lỗi hoặc sai lệch bất biến thì đánh dấu projection stale rồi ném lại lỗi. */
    void sync(Long loanId);
}
