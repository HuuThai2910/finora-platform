package com.finora.blockchain.service.proof;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.finora.blockchain.domain.proof.ProofChainCheckStatus;
import com.finora.blockchain.domain.proof.ProofHashScheme;
import com.finora.blockchain.domain.proof.ProofProviderType;
import com.finora.blockchain.domain.proof.ProofSubmission;
import com.finora.blockchain.domain.proof.ProofSubmissionStatus;
import com.finora.blockchain.dto.response.ProofVerificationResponse;
import com.finora.blockchain.integration.proof.LedgerProofRecord;
import com.finora.blockchain.integration.proof.ProofLedger;
import com.finora.blockchain.integration.proof.ProofLedgerException;
import com.finora.blockchain.repository.proof.ProofSubmissionRepository;
import com.finora.common.exception.BusinessException;
import java.time.Clock;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/**
 * Kiểm toàn vẹn một proof theo hai chiều (F08):
 * <ul>
 *   <li>Artifact → hash: tính lại SHA-256 từ {@code data} của event nguồn theo đúng scheme đã dùng.</li>
 *   <li>PostgreSQL ↔ Fabric: đọc {@code GetProof} trên chain và so hash, aggregate, txId.</li>
 * </ul>
 * Lời gọi Fabric nằm ngoài transaction; kết quả MATCHED/MISMATCHED/MISSING_ON_CHAIN được lưu bằng transaction ngắn.
 * Sai lệch chỉ được ghi nhận và log cảnh báo cho vận hành điều tra; không sửa dữ liệu phía nào.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ProofVerificationService {

    private final ProofSubmissionRepository repository;
    private final ProofSubmissionStateService stateService;
    private final ProofLedger ledger;
    private final ObjectMapper mapper;
    private final Clock clock;

    public ProofVerificationResponse verify(String proofId, JsonNode artifact) {
        ProofSubmission proof = ProofQueryService.load(repository, ProofQueryService.parseProofId(proofId));
        ProofVerificationResponse.ArtifactCheck artifactCheck = artifact == null || artifact.isNull()
                ? null : checkArtifact(proof, artifact);
        ProofVerificationResponse.ChainVerification chain = checkChain(proof);
        return new ProofVerificationResponse(proof.getProofId(), proof.getProvider(), proof.getStatus(),
                artifactCheck, chain, clock.instant());
    }

    /** Worker đối chiếu định kỳ gọi theo ID nội bộ. */
    public Optional<ProofChainCheckStatus> reconcile(Long databaseId) {
        Optional<ProofSubmission> proof = repository.findById(databaseId);
        if (proof.isEmpty()) {
            return Optional.empty();
        }
        String result = checkChain(proof.get()).result();
        try {
            return Optional.of(ProofChainCheckStatus.valueOf(result));
        } catch (IllegalArgumentException notPersisted) {
            return Optional.empty();
        }
    }

    private ProofVerificationResponse.ArtifactCheck checkArtifact(ProofSubmission proof, JsonNode artifact) {
        ProofHashScheme scheme = proof.hashScheme();
        String recomputed;
        try {
            recomputed = ProofPayloadHasher.hash(artifact, scheme, mapper);
        } catch (IllegalArgumentException exception) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "PROOF_ARTIFACT_INVALID",
                    "Artifact không chuẩn hóa được theo " + scheme.name() + ": " + exception.getMessage());
        }
        return new ProofVerificationResponse.ArtifactCheck(
                recomputed.equals(proof.getPayloadHash()) ? "MATCHED" : "MISMATCHED",
                scheme.name(), proof.getPayloadHash(), recomputed);
    }

    private ProofVerificationResponse.ChainVerification checkChain(ProofSubmission proof) {
        if (proof.getProvider() == ProofProviderType.MOCK) {
            return notChecked("NOT_ON_CHAIN_MOCK", "Proof ghi bằng ledger MOCK, không có bản ghi trên Fabric");
        }
        if (proof.getStatus() != ProofSubmissionStatus.CONFIRMED) {
            return notChecked("NOT_CONFIRMED", "Proof chưa được Fabric xác nhận (" + proof.getStatus() + ")");
        }
        if (ledger.provider() != ProofProviderType.HYPERLEDGER_FABRIC) {
            return notChecked("LEDGER_UNAVAILABLE", "Service đang chạy ledger MOCK, không đọc được Fabric");
        }
        Optional<LedgerProofRecord> record;
        try {
            record = ledger.read(proof.getProofId());
        } catch (ProofLedgerException exception) {
            return notChecked("LEDGER_UNAVAILABLE", exception.getErrorCode());
        }
        ProofChainCheckStatus result = record.isEmpty() ? ProofChainCheckStatus.MISSING_ON_CHAIN
                : matches(proof, record.get()) ? ProofChainCheckStatus.MATCHED : ProofChainCheckStatus.MISMATCHED;
        stateService.recordChainCheck(proof.getId(), result);
        if (result != ProofChainCheckStatus.MATCHED) {
            log.error("Sai lệch proof giữa PostgreSQL và Fabric, cần điều tra: proofId={}, result={}, proofType={}",
                    proof.getProofId(), result, proof.getProofType());
        }
        return record.map(onChain -> new ProofVerificationResponse.ChainVerification(result.name(), null,
                        onChain.txId(), onChain.payloadHash(), onChain.recordedAt(), onChain.submitterMspId()))
                .orElseGet(() -> new ProofVerificationResponse.ChainVerification(result.name(),
                        "Fabric không có proofId này", null, null, null, null));
    }

    private static boolean matches(ProofSubmission proof, LedgerProofRecord record) {
        UUID proofId = proof.getProofId();
        return proofId.equals(record.proofId())
                && proof.getProofType().name().equals(record.proofType())
                && proof.getAggregateType().equals(record.aggregateType())
                && proof.getAggregateId().equals(record.aggregateId())
                && proof.getPayloadHash().equals(record.payloadHash())
                && proof.getPayloadVersion() == record.payloadVersion()
                && proof.getSourceService().equals(record.sourceService())
                && proof.getProviderTransactionId().equals(record.txId());
    }

    private static ProofVerificationResponse.ChainVerification notChecked(String result, String detail) {
        return new ProofVerificationResponse.ChainVerification(result, detail, null, null, null, null);
    }
}
