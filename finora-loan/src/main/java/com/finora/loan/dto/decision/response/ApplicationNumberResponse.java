package com.finora.loan.dto.decision.response;

/** Cặp ID số và mã hồ sơ, cho web đổi "Hồ sơ #id" thành mã {@code LA-…}. */
public record ApplicationNumberResponse(Long id, String applicationNumber) {
}
