package com.finora.loan.dto.contract.request;

import com.finora.loan.domain.contract.ContractDeclineReasonCode;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

public record DeclineLoanContractRequest(
        @PositiveOrZero long version,
        @NotNull ContractDeclineReasonCode reasonCode,
        @Size(max = 1000) String reasonDetail
) {
}
