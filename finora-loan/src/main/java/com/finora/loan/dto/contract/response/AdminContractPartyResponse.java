package com.finora.loan.dto.contract.response;

import com.finora.loan.domain.contract.enums.ContractPartyStatus;
import com.finora.loan.domain.contract.enums.ContractPartyType;
import com.finora.loan.domain.contract.enums.SignatureProviderType;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Một bên ký của hợp đồng trong màn quản trị.
 *
 * @param partyId                ID người dùng ở finora-user (nhà đầu tư hoặc người vay); web đổi thành họ tên khi có quyền
 * @param commitmentId           phần vốn bên Investment; null với người vay
 * @param allocationAmount       số tiền góp của nhà đầu tư; với người vay là số tiền vay
 * @param allocationSharePercent tỷ lệ phần vốn; null với người vay
 * @param signedAt               lúc ký; null khi chưa ký
 * @param closedAt               lúc rời hợp đồng (từ chối hoặc hết hạn ký); null khi còn trong hợp đồng
 */
public record AdminContractPartyResponse(
        ContractPartyType partyType,
        String partyId,
        Long commitmentId,
        BigDecimal allocationAmount,
        BigDecimal allocationSharePercent,
        ContractPartyStatus status,
        SignatureProviderType signatureProvider,
        Instant signedAt,
        Instant closedAt
) {
}
