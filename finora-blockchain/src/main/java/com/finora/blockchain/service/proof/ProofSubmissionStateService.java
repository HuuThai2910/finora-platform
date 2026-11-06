package com.finora.blockchain.service.proof;

import com.finora.blockchain.domain.proof.enums.ProofChainCheckStatus;
import com.finora.blockchain.integration.fabric.contract.ProofLedgerReceipt;

import java.util.List;
import java.util.UUID;

/**
 * Các bước đổi trạng thái proof trong PostgreSQL cho worker ghi ledger và worker đối chiếu; mỗi bước là một
 * transaction ngắn và không gọi ledger.
 */
public interface ProofSubmissionStateService {

    /** Chỉ proof đã đăng ký cho provider đang chạy; proof của provider khác chờ tới khi provider đó được bật (B50). */
    List<Long> dueIds();

    /**
     * Claim proof để gửi ledger bằng token mới; trả {@code null} nếu không claim được. Proof mất processing lease đã
     * quá số lần thử thì chuyển DEAD_LETTER.
     */
    ClaimedProofSubmission claim(Long id);

    /** Lưu receipt của ledger cho proof đang được giữ bằng {@code claimToken}. */
    void markConfirmed(Long id, UUID claimToken, ProofLedgerReceipt receipt);

    /**
     * Ghi lỗi của lần gửi: chờ thử lại sau backoff tăng dần, hoặc DEAD_LETTER khi lỗi không thử lại được hay đã hết
     * số lần thử.
     */
    void markFailed(Long id, UUID claimToken, String code, String detail, boolean retryable);

    /** Proof Fabric đã xác nhận cần đối chiếu: chưa kiểm hoặc lần kiểm gần nhất đã quá {@code chain-check-interval}. */
    List<Long> chainCheckDueIds();

    /** Lưu kết quả đối chiếu gần nhất; trả false nếu proof không còn ở trạng thái đối chiếu được. */
    boolean recordChainCheck(Long id, ProofChainCheckStatus result);
}
