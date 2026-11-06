package com.finora.loan.service.core;

import com.finora.loan.dto.core.response.CoreProductSyncResponse;

/**
 * Đồng bộ Product sang Fineract qua command bền vững, dùng chung cho request của admin và retry worker.
 */
public interface CoreProductSyncService {

    /**
     * Trả lại command cũ khi admin gửi trùng cùng business key. Command mới hoặc
     * retry đến lượt sẽ được thực thi sau khi transaction chuẩn bị đã đóng.
     */
    CoreProductSyncResponse synchronize(long productId, long version);

    /** Thực thi một durable command; được dùng chung bởi request đầu tiên và retry worker. */
    void execute(String commandId);
}
