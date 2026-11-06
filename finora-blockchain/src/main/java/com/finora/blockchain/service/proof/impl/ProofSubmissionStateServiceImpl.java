package com.finora.blockchain.service.proof.impl;

import com.finora.blockchain.config.ProofSubmissionProperties;
import com.finora.blockchain.domain.proof.entity.ProofSubmission;
import com.finora.blockchain.domain.proof.enums.ProofChainCheckStatus;
import com.finora.blockchain.domain.proof.enums.ProofProviderType;
import com.finora.blockchain.domain.proof.enums.ProofSubmissionStatus;
import com.finora.blockchain.integration.fabric.contract.ProofLedgerCommand;
import com.finora.blockchain.integration.fabric.contract.ProofLedgerReceipt;
import com.finora.blockchain.repository.proof.ProofSubmissionRepository;
import com.finora.blockchain.service.proof.ClaimedProofSubmission;
import com.finora.blockchain.service.proof.ProofSubmissionStateService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Mỗi lần đổi state là transaction ngắn; lệnh gọi ledger luôn nằm ngoài class này. */
@Service
@RequiredArgsConstructor
public class ProofSubmissionStateServiceImpl implements ProofSubmissionStateService {

    private final ProofSubmissionRepository proofSubmissionRepository;
    private final ProofSubmissionProperties proofSubmissionProperties;
    private final Clock clock;

    @Override
    @Transactional(readOnly = true)
    public List<Long> dueIds() {
        Instant now = clock.instant();
        return proofSubmissionRepository.findDueIds(
                proofSubmissionProperties.provider(),
                ProofSubmissionStatus.PENDING,
                ProofSubmissionStatus.PROCESSING,
                now,
                now.minus(proofSubmissionProperties.processingLease()),
                PageRequest.of(0, proofSubmissionProperties.batchSize())
        );
    }

    @Override
    @Transactional
    public ClaimedProofSubmission claim(Long id) {
        ProofSubmission proof = proofSubmissionRepository.findByIdForUpdate(id).orElse(null);
        if (proof == null || proof.getProvider() != proofSubmissionProperties.provider()) {
            return null;
        }
        Instant now = clock.instant();
        Instant leaseExpiredBefore = now.minus(proofSubmissionProperties.processingLease());
        if (proof.getStatus() == ProofSubmissionStatus.PROCESSING
                && proof.getProcessingStartedAt() != null
                && !proof.getProcessingStartedAt().isAfter(leaseExpiredBefore)
                && proof.getAttemptCount() >= proofSubmissionProperties.maxAttempts()) {
            proof.fail(
                    proof.getProcessingToken(),
                    "PROOF_PROCESSING_LEASE_EXHAUSTED",
                    "Worker đã mất processing lease quá số lần cho phép",
                    false,
                    proofSubmissionProperties.maxAttempts(),
                    now,
                    now
            );
            proofSubmissionRepository.saveAndFlush(proof);
            return null;
        }

        UUID token = UUID.randomUUID();
        if (!proof.claim(now, leaseExpiredBefore, token)) {
            return null;
        }
        proofSubmissionRepository.saveAndFlush(proof);
        return new ClaimedProofSubmission(
                proof.getId(),
                token,
                proof.getProvider(),
                new ProofLedgerCommand(
                        proof.getProofId(), proof.getAggregateType(), proof.getAggregateId(), proof.getProofType(),
                        proof.getPayloadHash(), proof.getPayloadVersion(), proof.getSourceService())
        );
    }

    @Override
    @Transactional
    public void markConfirmed(Long id, UUID claimToken, ProofLedgerReceipt receipt) {
        ProofSubmission proof = proofSubmissionRepository.findByIdForUpdate(id).orElse(null);
        if (proof == null) {
            return;
        }
        proof.confirm(claimToken, receipt.transactionId(), receipt.blockReference(), clock.instant());
        proofSubmissionRepository.saveAndFlush(proof);
    }

    @Override
    @Transactional
    public void markFailed(Long id, UUID claimToken, String code, String detail, boolean retryable) {
        ProofSubmission proof = proofSubmissionRepository.findByIdForUpdate(id).orElse(null);
        if (proof == null) {
            return;
        }
        Instant now = clock.instant();
        proof.fail(
                claimToken, code, detail, retryable, proofSubmissionProperties.maxAttempts(),
                now.plus(backoff(proof.getAttemptCount())), now);
        proofSubmissionRepository.saveAndFlush(proof);
    }

    @Override
    @Transactional(readOnly = true)
    public List<Long> chainCheckDueIds() {
        return proofSubmissionRepository.findChainCheckDueIds(
                ProofSubmissionStatus.CONFIRMED,
                ProofProviderType.HYPERLEDGER_FABRIC,
                clock.instant().minus(proofSubmissionProperties.chainCheckInterval()),
                PageRequest.of(0, proofSubmissionProperties.batchSize()));
    }

    @Override
    @Transactional
    public boolean recordChainCheck(Long id, ProofChainCheckStatus result) {
        return proofSubmissionRepository.recordChainCheck(id, result, clock.instant(),
                ProofSubmissionStatus.CONFIRMED, ProofProviderType.HYPERLEDGER_FABRIC) == 1;
    }

    private Duration backoff(int attemptCount) {
        Duration delay = proofSubmissionProperties.retryBackoff();
        for (int attempt = 1;
                attempt < attemptCount && delay.compareTo(proofSubmissionProperties.maximumBackoff()) < 0; attempt++) {
            try {
                Duration doubled = delay.multipliedBy(2);
                delay = doubled.compareTo(proofSubmissionProperties.maximumBackoff()) > 0
                        ? proofSubmissionProperties.maximumBackoff()
                        : doubled;
            } catch (ArithmeticException overflow) {
                return proofSubmissionProperties.maximumBackoff();
            }
        }
        return delay;
    }
}
