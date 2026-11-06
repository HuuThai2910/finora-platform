package com.finora.blockchain.service.proof.impl;

import com.finora.blockchain.domain.proof.enums.ProofChainCheckStatus;
import com.finora.blockchain.domain.proof.enums.ProofProviderType;
import com.finora.blockchain.integration.fabric.client.ProofLedger;
import com.finora.blockchain.service.proof.ProofChainReconciliationWorker;
import com.finora.blockchain.service.proof.ProofSubmissionStateService;
import com.finora.blockchain.service.proof.ProofVerificationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.EnumMap;
import java.util.Map;

/**
 * Đối chiếu định kỳ PostgreSQL ↔ Fabric (F08 bước 3–4), opt-in. Mỗi lượt lấy tối đa {@code batch-size} proof Fabric đã
 * xác nhận chưa kiểm hoặc kiểm quá {@code chain-check-interval}; kết quả lưu trên proof, sai lệch log ERROR để vận
 * hành điều tra. Không đổi hash/receipt và không gọi lại service nguồn.
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "finora.blockchain.proof.reconciliation-enabled", havingValue = "true")
@RequiredArgsConstructor
public class ProofChainReconciliationWorkerImpl implements ProofChainReconciliationWorker {

    private final ProofSubmissionStateService proofSubmissionStateService;
    private final ProofVerificationService proofVerificationService;
    private final ProofLedger proofLedger;

    @Override
    @Scheduled(fixedDelayString = "${finora.blockchain.proof.reconciliation-delay:600000}",
            initialDelayString = "${finora.blockchain.proof.reconciliation-initial-delay:60000}")
    public void reconcile() {
        if (proofLedger.provider() != ProofProviderType.HYPERLEDGER_FABRIC) {
            return;
        }
        Map<ProofChainCheckStatus, Integer> counts = new EnumMap<>(ProofChainCheckStatus.class);
        for (Long id : proofSubmissionStateService.chainCheckDueIds()) {
            try {
                proofVerificationService.reconcile(id).ifPresent(result -> counts.merge(result, 1, Integer::sum));
            } catch (RuntimeException failure) {
                log.warn("Chưa đối chiếu được proof với Fabric: databaseId={}, exceptionType={}",
                        id, failure.getClass().getSimpleName());
            }
        }
        if (!counts.isEmpty()) {
            log.info("Đối chiếu proof với Fabric xong: {}", counts);
        }
    }
}
