package com.finora.loan.integration.signature;

/** Boundary thay thế được giữa mock phát triển và chữ ký số production. */
public interface SignatureProvider {

    /** Luồng bằng chứng tách rời (hợp đồng trước R5): provider ký hash của cả tệp PDF. */
    SignatureSubmission submit(SignatureCommand command);

    SignatureSubmission status(String providerTransactionId, String documentId);

    com.finora.loan.domain.contract.SignatureProviderType type();

    com.finora.loan.domain.contract.SignatureMethod method();

    /**
     * Chứng thư của người ký dùng để dựng signed attributes PAdES. Gọi ngoài DB transaction vì SmartCA là lời gọi mạng.
     *
     * @param signerId mã bên ký trong FINORA; UAT dùng một thuê bao test cố định nên chưa ánh xạ theo từng người
     */
    SigningCertificate signingCertificate(String signerId);

    /** Gửi digest SHA-256 của DER signed attributes để provider ký (VNPT {@code sign_type = hash}). */
    SignatureSubmission submitDigest(DigestSignatureCommand command);

    /**
     * {@code true} khi provider ký ngay trong tiến trình (MOCK): toàn bộ chuẩn bị → ký → nhúng chạy trong một
     * transaction đang khóa Contract. Provider từ xa trả {@code false} và mọi lời gọi mạng nằm ngoài transaction.
     */
    boolean completesSynchronously();
}
