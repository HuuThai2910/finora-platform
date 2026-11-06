package com.finora.loan.service.core;

/**
 * Worker định kỳ thực thi lại các command đồng bộ Product sang Fineract đã đến hạn.
 */
public interface CoreProductSyncRetryWorker {

    /** Một vòng: thực thi lần lượt các command đến hạn, command lỗi không chặn command sau. */
    void retryDueCommands();
}
