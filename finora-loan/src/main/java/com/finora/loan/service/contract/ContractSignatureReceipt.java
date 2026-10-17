package com.finora.loan.service.contract;

import com.finora.loan.domain.contract.ContractPartyType;
import com.finora.loan.domain.contract.SignatureMethod;
import com.finora.loan.domain.contract.SignatureProviderType;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * Dữ liệu trang biên nhận chữ ký số; mọi giá trị chữ ký lấy từ kết quả kiểm lại tệp đã ký, không lấy từ input client.
 * Chứng thư chỉ hiện CN, serial và tổ chức cấp, không in lại các thuộc tính định danh khác của subject.
 */
public record ContractSignatureReceipt(
        String contractNumber,
        String applicationNumber,
        String signableHash,
        String signableVersion,
        String signedPdfHash,
        int signedPdfLength,
        Instant issuedAt,
        List<Row> rows
) {
    public ContractSignatureReceipt {
        Objects.requireNonNull(contractNumber, "contractNumber");
        Objects.requireNonNull(issuedAt, "issuedAt");
        rows = List.copyOf(rows);
    }

    public record Row(
            int sequence,
            ContractPartyType signerType,
            String signerId,
            Instant signingTime,
            SignatureProviderType provider,
            SignatureMethod method,
            String certificateCommonName,
            String certificateSerial,
            String issuerCommonName,
            String certificateSha256,
            String revisionHash,
            int revisionLength
    ) {
    }
}
