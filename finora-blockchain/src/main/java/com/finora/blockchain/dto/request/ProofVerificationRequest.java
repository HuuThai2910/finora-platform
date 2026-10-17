package com.finora.blockchain.dto.request;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * @param artifact object {@code data} của event nguồn (ví dụ lấy từ outbox của Loan/Payment/Investment); bỏ trống thì
 *                 chỉ đối chiếu DB ↔ Fabric. Blockchain không lưu artifact này.
 */
public record ProofVerificationRequest(JsonNode artifact) {
}
