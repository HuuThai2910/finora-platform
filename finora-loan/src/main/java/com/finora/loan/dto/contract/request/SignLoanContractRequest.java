package com.finora.loan.dto.contract.request;

import com.finora.loan.domain.contract.SignatureMethod;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.PositiveOrZero;

public record SignLoanContractRequest(
        @PositiveOrZero long version,
        @NotBlank @Pattern(regexp = "^[0-9a-f]{64}$") String documentHash,
        @NotNull SignatureMethod signatureMethod
) {
}
