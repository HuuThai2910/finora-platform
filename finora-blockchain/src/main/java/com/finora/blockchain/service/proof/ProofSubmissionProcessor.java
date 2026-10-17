package com.finora.blockchain.service.proof;

import com.finora.blockchain.domain.proof.ProofReceiptFormat;
import com.finora.blockchain.integration.proof.ProofLedger;
import com.finora.blockchain.integration.proof.ProofLedgerException;
import com.finora.blockchain.integration.proof.ProofLedgerReceipt;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * Claim DB, gọi provider ngoài transaction, rồi xác nhận kết quả bằng một transaction mới.
 *
 * <p>Nếu process dừng sau khi Fabric đã commit nhưng trước khi lưu receipt, proof còn {@code PROCESSING}; hết lease
 * thì worker khác claim lại và adapter Fabric đọc {@code GetProof} trước khi ghi nên nhận đúng giao dịch gốc.</p>
 */
@Service
@RequiredArgsConstructor
public class ProofSubmissionProcessor {

    private final ProofSubmissionStateService stateService;
    private final ProofLedger proofLedger;

    public void process(Long id) {
        ClaimedProofSubmission claimed = stateService.claim(id);
        if (claimed == null) {
            return;
        }
        if (claimed.provider() != proofLedger.provider()) {
            // Không bao giờ xảy ra khi claim lọc theo provider; giữ để receipt MOCK/Fabric không thể bị gán nhầm (B50).
            stateService.markFailed(claimed.databaseId(), claimed.claimToken(), "PROOF_PROVIDER_NOT_ACTIVE",
                    "Provider đang chạy không phải provider đã đăng ký cho proof", true);
            return;
        }
        ProofLedgerReceipt receipt;
        try {
            receipt = proofLedger.submit(claimed.command());
        } catch (ProofLedgerException failure) {
            stateService.markFailed(
                    claimed.databaseId(), claimed.claimToken(), failure.getErrorCode(),
                    limited(failure.getMessage()), failure.isRetryable());
            return;
        } catch (RuntimeException failure) {
            stateService.markFailed(
                    claimed.databaseId(), claimed.claimToken(), "PROOF_LEDGER_UNEXPECTED",
                    limited(failure.getMessage()), true);
            return;
        }
        if (receipt == null
                || !ProofReceiptFormat.matches(claimed.provider(), receipt.transactionId(), receipt.blockReference())) {
            stateService.markFailed(claimed.databaseId(), claimed.claimToken(), "PROOF_RECEIPT_PROVIDER_MISMATCH",
                    "Receipt không đúng định dạng của provider " + claimed.provider(), false);
            return;
        }
        stateService.markConfirmed(claimed.databaseId(), claimed.claimToken(), receipt);
    }

    private static String limited(String detail) {
        if (detail == null || detail.isBlank()) {
            return null;
        }
        String normalized = detail.trim();
        return normalized.length() <= 500 ? normalized : normalized.substring(0, 500);
    }
}
