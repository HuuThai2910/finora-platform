package com.finora.payment.dto.request;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;

public record CreateTopUpRequest(
        @NotNull
        @DecimalMin(value = "10000", message = "Số tiền nạp tối thiểu là 10.000 đồng")
        @DecimalMax(value = "100000000", message = "Số tiền nạp tối đa là 100.000.000 đồng")
        @Digits(integer = 12, fraction = 0, message = "Số tiền nạp phải là số nguyên đồng")
        BigDecimal amount
) {
}
