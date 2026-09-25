package com.finora.loan.dto.application.request;

import com.finora.loan.domain.contract.ContractDeclineReasonCode;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record DeclineLoanTermsRequest(
        @NotNull Long applicationVersion,
        @NotBlank @Size(max = 50) String termsVersion,
        @NotBlank @Size(min = 64, max = 64) String termsHash,
        @NotNull ContractDeclineReasonCode reasonCode,
        @Size(max = 1000) String reasonDetail
) {
}
