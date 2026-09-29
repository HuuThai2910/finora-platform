package com.finora.payment.dto.response;

import com.finora.payment.domain.hold.PaymentHold;
import java.math.BigDecimal;

public record HoldResponse(String holdReference, BigDecimal amount, String currency, String status) {
    public static HoldResponse from(PaymentHold hold) {
        return new HoldResponse(
                hold.getHoldReference(), hold.getAmount(), hold.getCurrency(), hold.getStatus().name());
    }
}
