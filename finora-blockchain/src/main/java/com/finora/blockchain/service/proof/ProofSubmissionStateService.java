package com.finora.blockchain.service.proof;

import com.finora.blockchain.config.ProofSubmissionProperties;
import com.finora.blockchain.domain.proof.ProofChainCheckStatus;
import com.finora.blockchain.domain.proof.ProofProviderType;
import com.finora.blockchain.domain.proof.ProofSubmission;
import com.finora.blockchain.domain.proof.ProofSubmissionStatus;
import com.finora.blockchain.integration.proof.ProofLedgerCommand;
import com.finora.blockchain.integration.proof.ProofLedgerReceipt;
import com.finora.blockchain.repository.proof.ProofSubmissionRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Mỗi lần đổi state là transaction ngắn; lệnh gọi ledger luôn nằm ngoài class này. */
@Service
@RequiredArgsConstructor
public class ProofSubmissionStateService {

    private final ProofSubmissionRepository repository;
    private final ProofSubmissionProperties properties;
    private final Clock clock;

    /** Chỉ proof đã đăng ký cho provider đang chạy; proof của provider khác chờ tới khi provider đó được bật (B50). */
    @Transactional(readOnly = true)
    public List<Long> dueIds() {
        Instant now = clock.instant();
        return repository.findDueIds(
                properties.provider(),
                ProofSubmissionStatus.PENDING,
                ProofSubmissionStatus.PROCESSING,
                now,
                now.minus(properties.processingLease()),
                PageRequest.of(0, properties.batchSize())
        );
    }

    @Transactional
    public ClaimedProofSubmission claim(Long id) {
        ProofSubmission proof = repository.findByIdForUpdate(id).orElse(null);
        if (proof == null || proof.getProvider() != properties.provider()) {
            return null;
        }
        Instant now = clock.instant();
        Instant leaseExpiredBefore = now.minus(properties.processingLease());
        if (proof.getStatus() == ProofSubmissionStatus.PROCESSING
                && proof.getProcessingStartedAt() != null
                && !proof.getProcessingStartedAt().isAfter(leaseExpiredBefore)
                && proof.getAttemptCount() >= properties.maxAttempts()) {
            proof.fail(
                    proof.getProcessingToken(),
                    "PROOF_PROCESSING_LEASE_EXHAUSTED",
                    "Worker đã mất processing lease quá số lần cho phép",
                    false,
                    properties.maxAttempts(),
                    now,
                    now
            );
            repository.saveAndFlush(proof);
            return null;
        }

        UUID token = UUID.randomUUID();
        if (!proof.claim(now, leaseExpiredBefore, token)) {
            return null;
        }
        repository.saveAndFlush(proof);
        return new ClaimedProofSubmission(
                proof.getId(),
                token,
                proof.getProvider(),
                new ProofLedgerCommand(
                        proof.getProofId(), proof.getAggregateType(), proof.getAggregateId(), proof.getProofType(),
                        proof.getPayloadHash(), proof.getPayloadVersion(), proof.getSourceService())
        );
    }

    @Transactional
    public void markConfirmed(Long id, UUID claimToken, ProofLedgerReceipt receipt) {
        ProofSubmission proof = repository.findByIdForUpdate(id).orElse(null);
        if (proof == null) {
            return;
        }
        proof.confirm(claimToken, receipt.transactionId(), receipt.blockReference(), clock.instant());
        repository.saveAndFlush(proof);
    }

    @Transactional
    public void markFailed(Long id, UUID claimToken, String code, String detail, boolean retryable) {
        ProofSubmission proof = repository.findByIdForUpdate(id).orElse(null);
        if (proof == null) {
            return;
        }
        Instant now = clock.instant();
        proof.fail(
                claimToken, code, detail, retryable, properties.maxAttempts(),
                now.plus(backoff(proof.getAttemptCount())), now);
        repository.saveAndFlush(proof);
    }

    @Transactional(readOnly = true)
    public List<Long> chainCheckDueIds() {
        return repository.findChainCheckDueIds(
                ProofSubmissionStatus.CONFIRMED,
                ProofProviderType.HYPERLEDGER_FABRIC,
                clock.instant().minus(properties.chainCheckInterval()),
                PageRequest.of(0, properties.batchSize()));
    }

    /** Lưu kết quả đối chiếu gần nhất; trả false nếu proof không còn ở trạng thái đối chiếu được. */
    @Transactional
    public boolean recordChainCheck(Long id, ProofChainCheckStatus result) {
        return repository.recordChainCheck(id, result, clock.instant(),
                ProofSubmissionStatus.CONFIRMED, ProofProviderType.HYPERLEDGER_FABRIC) == 1;
    }

    private Duration backoff(int attemptCount) {
        Duration delay = properties.retryBackoff();
        for (int attempt = 1; attempt < attemptCount && delay.compareTo(properties.maximumBackoff()) < 0; attempt++) {
            try {
                Duration doubled = delay.multipliedBy(2);
                delay = doubled.compareTo(properties.maximumBackoff()) > 0
                        ? properties.maximumBackoff()
                        : doubled;
            } catch (ArithmeticException overflow) {
                return properties.maximumBackoff();
            }
        }
        return delay;
    }
}
