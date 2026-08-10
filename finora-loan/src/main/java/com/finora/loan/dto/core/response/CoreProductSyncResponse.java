package com.finora.loan.dto.core.response;

import com.finora.loan.domain.core.FineractCommandStatus;
import com.finora.loan.dto.product.response.LoanProductResponse;

public record CoreProductSyncResponse(
        LoanProductResponse product,
        String commandId,
        FineractCommandStatus commandStatus,
        String errorCode
) {
}
