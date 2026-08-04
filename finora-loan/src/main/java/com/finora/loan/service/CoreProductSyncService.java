package com.finora.loan.service;

import com.finora.loan.dto.response.CoreProductSyncResponse;

public interface CoreProductSyncService {

    CoreProductSyncResponse synchronize(long productId, long version);
}
