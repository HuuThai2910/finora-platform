package com.finora.loan.repository.contract;

import com.finora.loan.domain.contract.ContractPartyType;
import java.time.Instant;

/** Một phiên ký PAdES đang chờ provider; worker dùng để đối soát mà không tải bytes PDF. */
public record InFlightPdfSignature(
        Long signatureId,
        String contractNumber,
        ContractPartyType signerType,
        String signerId,
        Instant preparedAt
) {
}
