package com.finora.blockchain.service.proof.impl;

import com.finora.blockchain.domain.proof.model.ProofReceiptFormat;
import com.finora.blockchain.integration.fabric.ProofLedgerException;
import com.finora.blockchain.integration.fabric.client.ProofLedger;
import com.finora.blockchain.integration.fabric.contract.ProofLedgerReceipt;
import com.finora.blockchain.service.proof.ClaimedProofSubmission;
import com.finora.blockchain.service.proof.ProofSubmissionProcessor;
import com.finora.blockchain.service.proof.ProofSubmissionStateService;
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
public class ProofSubmissionProcessorImpl implements ProofSubmissionProcessor {

    private final ProofSubmissionStateService proofSubmissionStateService;
    private final ProofLedger proofLedger;

    @Override
    public void process(Long id) {
        ClaimedProofSubmission claimed = proofSubmissionStateService.claim(id);
        if (claimed == null) {
            return;
        }
        if (claimed.provider() != proofLedger.provider()) {
            // Không bao giờ xảy ra khi claim lọc theo provider; giữ để receipt MOCK/Fabric không thể bị gán nhầm (B50).
            proofSubmissionStateService.markFailed(claimed.databaseId(), claimed.claimToken(),
                    "PROOF_PROVIDER_NOT_ACTIVE", "Provider đang chạy không phải provider đã đăng ký cho proof", true);
            return;
        }
        ProofLedgerReceipt receipt;
        try {
            receipt = proofLedger.submit(claimed.command());
        } catch (ProofLedgerException failure) {
            proofSubmissionStateService.markFailed(
                    claimed.databaseId(), claimed.claimToken(), failure.getErrorCode(),
                    limited(failure.getMessage()), failure.isRetryable());
            return;
        } catch (RuntimeException failure) {
            proofSubmissionStateService.markFailed(
                    claimed.databaseId(), claimed.claimToken(), "PROOF_LEDGER_UNEXPECTED",
                    limited(failure.getMessage()), true);
            return;
        }
        if (receipt == null
                || !ProofReceiptFormat.matches(claimed.provider(), receipt.transactionId(), receipt.blockReference())) {
            proofSubmissionStateService.markFailed(claimed.databaseId(), claimed.claimToken(),
                    "PROOF_RECEIPT_PROVIDER_MISMATCH",
                    "Receipt không đúng định dạng của provider " + claimed.provider(), false);
            return;
        }
        proofSubmissionStateService.markConfirmed(claimed.databaseId(), claimed.claimToken(), receipt);
    }

    private static String limited(String detail) {
        if (detail == null || detail.isBlank()) {
            return null;
        }
        String normalized = detail.trim();
        return normalized.length() <= 500 ? normalized : normalized.substring(0, 500);
    }
}
