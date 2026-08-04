package com.finora.loan.dto.response;

import com.finora.loan.domain.FineractCommandStatus;

public record CoreProductSyncResponse(
        LoanProductResponse product,
        String commandId,
        FineractCommandStatus commandStatus,
        String errorCode
) {
}
