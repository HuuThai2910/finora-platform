package com.finora.loan.service.core;

import com.finora.loan.dto.core.response.CoreProductSyncResponse;

public interface CoreProductSyncService {

    CoreProductSyncResponse synchronize(long productId, long version);

    /** Thực thi một durable command; được dùng chung bởi request đầu tiên và retry worker. */
    void execute(String commandId);
}
