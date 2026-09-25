package com.finora.blockchain.service.proof;

import com.finora.blockchain.integration.proof.ProofLedger;
import com.finora.blockchain.integration.proof.ProofLedgerException;
import com.finora.blockchain.integration.proof.ProofLedgerReceipt;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/** Claim DB, gọi provider ngoài transaction, rồi xác nhận kết quả bằng một transaction mới. */
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
        try {
            ProofLedgerReceipt receipt = proofLedger.submit(claimed.command());
            stateService.markConfirmed(claimed.databaseId(), claimed.claimToken(), receipt);
        } catch (ProofLedgerException failure) {
            stateService.markFailed(
                    claimed.databaseId(), claimed.claimToken(), failure.getErrorCode(),
                    limited(failure.getMessage()), failure.isRetryable());
        } catch (RuntimeException failure) {
            stateService.markFailed(
                    claimed.databaseId(), claimed.claimToken(), "PROOF_LEDGER_UNEXPECTED",
                    limited(failure.getMessage()), true);
        }
    }

    private static String limited(String detail) {
        if (detail == null || detail.isBlank()) {
            return null;
        }
        String normalized = detail.trim();
        return normalized.length() <= 500 ? normalized : normalized.substring(0, 500);
    }
}
