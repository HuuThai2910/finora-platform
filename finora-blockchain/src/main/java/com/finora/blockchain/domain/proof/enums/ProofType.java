package com.finora.blockchain.domain.proof.enums;

/**
 * Loại bằng chứng được phép ghi; payload gốc luôn ở service nguồn. Mốc nghiệp vụ của R6 (W3D, D52): hợp đồng đủ chữ
 * ký, giải ngân, trả nợ, phát hành Note, chuyển nhượng Note; thêm giấy xác nhận chuyển nhượng Note (PDF ký PAdES) do
 * Investment cấp khi giao dịch sổ lệnh đã tất toán. Thêm loại mới phải nâng chaincode (allowlist trên chain).
 *
 * <p>{@link #NOTE_OWNERSHIP} là một loại bản ghi trên chain cho hai nghiệp vụ riêng biệt, mỗi lần là một proof riêng
 * theo aggregate: phát hành Note cho nhà đầu tư góp vốn vào hồ sơ mới ({@code NoteIssuance/<listingId>}) và chuyển
 * nhượng Note giữa các nhà đầu tư trên sổ lệnh ({@code NoteTransfer/<tradeReference>}). Giao diện hiện theo aggregate.
 */
public enum ProofType {
    CONTRACT_DOCUMENT,
    DISBURSEMENT,
    REPAYMENT,
    NOTE_OWNERSHIP,
    NOTE_TRANSFER_DOCUMENT
}
