package com.finora.blockchain.domain.proof;

/**
 * Loại bằng chứng được phép ghi; payload gốc luôn ở service nguồn. Bốn mốc nghiệp vụ của R6 (W3D, D52): hợp đồng đủ
 * chữ ký, giải ngân, trả nợ, phát hành/chuyển nhượng Note; thêm giấy xác nhận chuyển nhượng Note (PDF ký PAdES) do
 * Investment cấp khi giao dịch sổ lệnh đã tất toán. Thêm loại mới phải nâng chaincode (allowlist trên chain).
 */
public enum ProofType {
    CONTRACT_DOCUMENT,
    DISBURSEMENT,
    REPAYMENT,
    NOTE_OWNERSHIP,
    NOTE_TRANSFER_DOCUMENT
}
