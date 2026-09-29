package com.finora.payment.dto.response;

public record TransferResponse(String paymentReference, boolean replayed) {
}
