package com.finora.loan.dto.contract.response;

import com.finora.loan.domain.contract.enums.SignatureProviderType;

/**
 * Cách ký hợp đồng đang áp dụng, cho màn quản trị nói trước khi khoản vay đủ vốn và có hợp đồng.
 *
 * @param signatureProvider    nhà cung cấp chữ ký số Loan đang cấu hình
 * @param signatureWindowHours thời hạn ký tính từ lúc phát hành hợp đồng, theo giờ
 */
public record AdminContractSigningPolicyResponse(
        SignatureProviderType signatureProvider,
        long signatureWindowHours
) {
}
