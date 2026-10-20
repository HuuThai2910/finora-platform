package com.finora.blockchain.integration.proof;

import com.finora.blockchain.domain.proof.ProofProviderType;
import java.util.Optional;
import java.util.UUID;

/** Port ghi/đọc bằng chứng; implementation không được nhận payload gốc hoặc PII. */
public interface ProofLedger {

    /** Provider mà receipt của ledger này thuộc về; worker chỉ gửi proof đã đăng ký cho đúng provider (B50). */
    ProofProviderType provider();

    /**
     * Ghi proof idempotent: gửi lại cùng proof sau timeout/restart trả lại receipt của lần ghi đầu, không tạo bản ghi
     * logic thứ hai. Lỗi được báo bằng {@link ProofLedgerException} kèm cờ retryable.
     */
    ProofLedgerReceipt submit(ProofLedgerCommand command);

    /** Đọc bản ghi trên ledger để đối chiếu; rỗng nghĩa là ledger chưa có proofId này. */
    Optional<LedgerProofRecord> read(UUID proofId);
}
