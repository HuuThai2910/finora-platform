package com.finora.loan.service.contract;

import com.finora.common.exception.ResourceNotFoundException;
import com.finora.common.logging.TraceContext;
import com.finora.loan.config.LoanContractProperties;
import com.finora.loan.domain.application.ActorType;
import com.finora.loan.domain.application.LoanApplication;
import com.finora.loan.domain.contract.ConsentAction;
import com.finora.loan.domain.contract.ContractPdfArtifactType;
import com.finora.loan.domain.contract.LoanContract;
import com.finora.loan.domain.contract.LoanContractDocument;
import com.finora.loan.domain.contract.LoanContractStatus;
import com.finora.loan.domain.contract.LoanContractStatusHistory;
import com.finora.loan.domain.core.ScheduleCalculationSnapshot;
import com.finora.loan.dto.contract.request.DeclineLoanContractRequest;
import com.finora.loan.dto.contract.request.SignLoanContractRequest;
import com.finora.loan.exception.LoanBusinessException;
import com.finora.loan.repository.contract.LoanContractRepository;
import com.finora.loan.repository.contract.LoanContractDocumentRepository;
import com.finora.loan.repository.contract.LoanContractStatusHistoryRepository;
import com.finora.loan.repository.application.LoanApplicationRepository;
import com.finora.loan.repository.core.ScheduleCalculationSnapshotRepository;
import com.finora.loan.support.HashingService;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class LoanContractStateService {

    private final LoanContractRepository contractRepository;
    private final LoanContractDocumentRepository contractDocumentRepository;
    private final LoanContractStatusHistoryRepository historyRepository;
    private final LoanApplicationRepository applicationRepository;
    private final ScheduleCalculationSnapshotRepository scheduleRepository;
    private final ContractPdfRenderer pdfRenderer;
    private final HashingService hashingService;
    private final LoanContractProperties properties;
    private final Clock clock;

    /** Consent và history cùng transaction; expiry được commit trước khi API trả lỗi hết hạn. */
    @Transactional
    public ContractConsentResult sign(
            String contractNumber,
            String idempotencyKey,
            String requestHash,
            SignLoanContractRequest request,
            String actorId
    ) {
        ContractConsentResult duplicate = duplicateConsent(
                contractNumber, idempotencyKey, requestHash, ConsentAction.SIGN, actorId);
        if (duplicate != null) {
            return duplicate;
        }
        LoanContract contract = lockedContract(contractNumber);
        contract.requireOwner(actorId);
        Instant now = clock.instant();
        if (expireDuringConsent(contract, now)) {
            return new ContractConsentResult(contract, true);
        }
        LoanContractDocument signablePdf = contractDocumentRepository
                .findByContractIdAndArtifactType(contract.getId(), ContractPdfArtifactType.SIGNABLE)
                .orElse(null);
        verifyPdfConsent(signablePdf, request.pdfDocumentHash());
        contract.sign(request.version(), request.documentHash(), request.signatureMethod(),
                idempotencyKey, requestHash, actorId, now);
        contractRepository.saveAndFlush(contract);
        if (signablePdf != null) {
            LoanApplication application = applicationRepository.findById(contract.getApplicationId())
                    .orElseThrow(() -> new ResourceNotFoundException(
                            "Loan Application", "id", contract.getApplicationId()));
            ScheduleCalculationSnapshot schedule = scheduleRepository.findById(contract.getCalculationSnapshotId())
                    .orElseThrow(() -> new ResourceNotFoundException(
                            "Schedule Calculation Snapshot", "id", contract.getCalculationSnapshotId()));
            ContractPdfArtifact receipt = pdfRenderer.renderSignedReceipt(
                    contract, application, schedule, signablePdf.getContentHash());
            contractDocumentRepository.saveAndFlush(LoanContractDocument.create(
                    contract.getId(), receipt.artifactType(), receipt.documentVersion(),
                    receipt.contentHash(), receipt.content(), now
            ));
        }
        saveHistory(contract, LoanContractStatus.PENDING_SIGNATURE, LoanContractStatus.SIGNED,
                "CONTRACT_SIGNED", ActorType.BORROWER, actorId, now);
        return new ContractConsentResult(contract, false);
    }

    private void verifyPdfConsent(LoanContractDocument signablePdf, String expectedPdfHash) {
        if (signablePdf == null) {
            return;
        }
        if (expectedPdfHash == null || expectedPdfHash.isBlank()
                || !signablePdf.getContentHash().equals(expectedPdfHash)) {
            throw LoanBusinessException.conflict(
                    "CONTRACT_PDF_MISMATCH",
                    "Bản PDF hợp đồng đã xem không khớp phiên bản cần ký"
            );
        }
        if (!signablePdf.getContentHash().equals(hashingService.sha256Bytes(signablePdf.contentCopy()))) {
            throw LoanBusinessException.conflict(
                    "CONTRACT_PDF_INTEGRITY_FAILED",
                    "Bản PDF hợp đồng lưu trữ không vượt qua kiểm tra toàn vẹn"
            );
        }
    }

    /** Decline là terminal transition riêng, không đổi ngược Application APPROVED. */
    @Transactional
    public ContractConsentResult decline(
            String contractNumber,
            String idempotencyKey,
            String requestHash,
            DeclineLoanContractRequest request,
            String actorId
    ) {
        ContractConsentResult duplicate = duplicateConsent(
                contractNumber, idempotencyKey, requestHash, ConsentAction.DECLINE, actorId);
        if (duplicate != null) {
            return duplicate;
        }
        LoanContract contract = lockedContract(contractNumber);
        contract.requireOwner(actorId);
        Instant now = clock.instant();
        if (expireDuringConsent(contract, now)) {
            return new ContractConsentResult(contract, true);
        }
        contract.decline(request.version(), request.reasonCode(), request.reasonDetail(),
                idempotencyKey, requestHash, actorId, now);
        contractRepository.saveAndFlush(contract);
        saveHistory(contract, LoanContractStatus.PENDING_SIGNATURE, LoanContractStatus.DECLINED,
                request.reasonCode().name(), ActorType.BORROWER, actorId, now);
        return new ContractConsentResult(contract, false);
    }

    /** Mỗi Contract chạy một transaction ngắn; worker không giữ lock cho cả batch. */
    @Transactional
    public boolean expireOne(Long contractId) {
        LoanContract contract = contractRepository.findByIdForUpdate(contractId).orElse(null);
        if (contract == null) {
            return false;
        }
        Instant now = clock.instant();
        if (!contract.expireIfDue(now)) {
            return false;
        }
        contractRepository.saveAndFlush(contract);
        saveHistory(contract, LoanContractStatus.PENDING_SIGNATURE, LoanContractStatus.EXPIRED,
                "SIGNATURE_WINDOW_EXPIRED", ActorType.SYSTEM, "SYSTEM", now);
        return true;
    }

    @Transactional(readOnly = true)
    public List<Long> dueIds() {
        return contractRepository.findDueIds(
                LoanContractStatus.PENDING_SIGNATURE,
                clock.instant(),
                PageRequest.of(0, properties.expiryBatchSize())
        );
    }

    @Transactional(readOnly = true)
    public ContractConsentResult findCommittedByKey(
            String contractNumber,
            String idempotencyKey,
            String requestHash,
            ConsentAction action,
            String actorId
    ) {
        // Dùng lại cùng một quy tắc để race và request tuần tự đều trả đúng IDEMPOTENCY_KEY_REUSED.
        return duplicateConsent(contractNumber, idempotencyKey, requestHash, action, actorId);
    }

    private ContractConsentResult duplicateConsent(
            String contractNumber,
            String idempotencyKey,
            String requestHash,
            ConsentAction action,
            String actorId
    ) {
        LoanContract existing = contractRepository.findByConsentIdempotencyKey(idempotencyKey).orElse(null);
        if (existing == null) {
            return null;
        }
        existing.requireOwner(actorId);
        if (!existing.getContractNumber().equals(contractNumber)
                || !existing.isSameConsent(idempotencyKey, requestHash, action)) {
            throw LoanBusinessException.conflict(
                    "IDEMPOTENCY_KEY_REUSED",
                    "Idempotency-Key đã được dùng cho một consent khác"
            );
        }
        return new ContractConsentResult(existing, false);
    }

    private boolean expireDuringConsent(LoanContract contract, Instant now) {
        if (!contract.expireIfDue(now)) {
            return false;
        }
        contractRepository.saveAndFlush(contract);
        saveHistory(contract, LoanContractStatus.PENDING_SIGNATURE, LoanContractStatus.EXPIRED,
                "SIGNATURE_WINDOW_EXPIRED", ActorType.SYSTEM, "SYSTEM", now);
        return true;
    }

    private LoanContract lockedContract(String contractNumber) {
        return contractRepository.findByContractNumberForUpdate(contractNumber)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Loan Contract", "contractNumber", contractNumber));
    }

    private void saveHistory(
            LoanContract contract,
            LoanContractStatus from,
            LoanContractStatus to,
            String reasonCode,
            ActorType actorType,
            String actorId,
            Instant now
    ) {
        historyRepository.saveAndFlush(LoanContractStatusHistory.create(
                contract.getId(), from, to, reasonCode, actorType, actorId,
                now, TraceContext.currentTraceIdOrCreate()));
    }
}
