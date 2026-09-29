package com.finora.payment.dto.request;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;

public record TransferRequest(
        @NotBlank @Size(max = 100) String buyerId,
        @NotBlank @Size(max = 100) String sellerId,
        @NotNull @DecimalMin("1") @Digits(integer = 17, fraction = 2) BigDecimal price,
        @NotNull @DecimalMin("0") @Digits(integer = 17, fraction = 2) BigDecimal platformFee,
        @NotBlank @Size(max = 100) String transferReference
) {
}
