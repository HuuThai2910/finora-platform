package com.finora.loan.service.contract;

import com.finora.common.exception.ResourceNotFoundException;
import com.finora.common.logging.TraceContext;
import com.finora.loan.config.LoanContractProperties;
import com.finora.loan.domain.application.ActorType;
import com.finora.loan.domain.application.LoanApplication;
import com.finora.loan.domain.contract.ConsentAction;
import com.finora.loan.domain.contract.ContractParty;
import com.finora.loan.domain.contract.ContractPartyType;
import com.finora.loan.domain.contract.ContractPdfArtifactType;
import com.finora.loan.domain.contract.LoanContract;
import com.finora.loan.domain.contract.LoanContractDocument;
import com.finora.loan.domain.contract.LoanContractStatus;
import com.finora.loan.domain.contract.LoanContractStatusHistory;
import com.finora.loan.domain.core.ScheduleCalculationSnapshot;
import com.finora.loan.dto.contract.request.DeclineLoanContractRequest;
import com.finora.loan.dto.contract.request.SignLoanContractRequest;
import com.finora.loan.exception.LoanBusinessException;
import com.finora.loan.integration.signature.SignatureEvidence;
import com.finora.loan.messaging.event.LoanContractClosedWithoutSignatureEventData;
import com.finora.loan.messaging.event.LoanContractSignedEventData;
import com.finora.loan.messaging.event.LoanContractActivatedEventData;
import com.finora.loan.repository.contract.LoanContractRepository;
import com.finora.loan.repository.contract.ContractPartyRepository;
import com.finora.loan.repository.contract.LoanContractDocumentRepository;
import com.finora.loan.repository.contract.LoanContractStatusHistoryRepository;
import com.finora.loan.repository.application.LoanApplicationRepository;
import com.finora.loan.repository.core.ScheduleCalculationSnapshotRepository;
import com.finora.loan.support.HashingService;
import com.finora.loan.service.outbox.OutboxService;
import com.finora.loan.service.disbursement.DisbursementSagaService;
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
    private final ContractPartyRepository partyRepository;
    private final LoanContractDocumentRepository contractDocumentRepository;
    private final LoanContractStatusHistoryRepository historyRepository;
    private final LoanApplicationRepository applicationRepository;
    private final ScheduleCalculationSnapshotRepository scheduleRepository;
    private final ContractPdfRenderer pdfRenderer;
    private final HashingService hashingService;
    private final LoanContractProperties properties;
    private final Clock clock;
    private final OutboxService outboxService;
    private final DisbursementSagaService disbursementSagaService;

    /**
     * Kiểm tra nhanh trước external call; transaction commit vẫn kiểm tra lại dưới pessimistic lock.
     * Cách tách này tránh giữ DB lock trong lúc chờ provider chữ ký.
     */
    @Transactional(readOnly = true)
    public SignaturePreparation prepareSignature(
            String contractNumber,
            SignLoanContractRequest request,
            String actorId
    ) {
        LoanContract contract = contractRepository.findByContractNumber(contractNumber)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Loan Contract", "contractNumber", contractNumber));
        contract.validateSignature(request.version(), request.documentHash(), actorId, clock.instant());
        LoanContractDocument signablePdf = contractDocumentRepository
                .findByContractIdAndArtifactType(contract.getId(), ContractPdfArtifactType.SIGNABLE)
                .orElse(null);
        verifyPdfConsent(signablePdf, request.pdfDocumentHash());
        String pdfHash = signablePdf == null ? contract.getDocumentHash() : signablePdf.getContentHash();
        return new SignaturePreparation(
                contract.getContractNumber(), contract.getBorrowerId(), contract.getDocumentHash(), pdfHash);
    }

    /** Consent và history cùng transaction; expiry được commit trước khi API trả lỗi hết hạn. */
    @Transactional
    public ContractConsentResult sign(
            String contractNumber,
            String idempotencyKey,
            String requestHash,
            SignLoanContractRequest request,
            SignatureEvidence evidence,
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
        LoanContractStatus previousStatus = contract.getStatus();
        contract.sign(request.version(), request.documentHash(), evidence.method(), evidence.provider(),
                evidence.providerTransactionId(), evidence.evidenceHash(),
                idempotencyKey, requestHash, actorId, now);
        recordBorrowerPartySignature(contract, idempotencyKey, requestHash, actorId, now);
        contractRepository.saveAndFlush(contract);
        if (signablePdf != null) {
            LoanApplication application = applicationRepository.findById(contract.getApplicationId())
                    .orElseThrow(() -> new ResourceNotFoundException(
                            "Loan Application", "id", contract.getApplicationId()));
            ScheduleCalculationSnapshot schedule = scheduleRepository.findById(contract.getCalculationSnapshotId())
                    .orElseThrow(() -> new ResourceNotFoundException(
                            "Schedule Calculation Snapshot", "id", contract.getCalculationSnapshotId()));
            ContractPdfArtifact receipt = signedReceipt(
                    contract, application, schedule, signablePdf.getContentHash());
            contractDocumentRepository.saveAndFlush(LoanContractDocument.create(
                    contract.getId(), receipt.artifactType(), receipt.documentVersion(),
                    receipt.contentHash(), receipt.content(), now
            ));
        }
        saveHistory(contract, previousStatus, LoanContractStatus.SIGNED,
                "CONTRACT_SIGNED", ActorType.BORROWER, actorId, now);
        outboxService.record(
                "LoanContract",
                contract.getContractNumber(),
                "LoanContractSigned",
                1,
                new LoanContractSignedEventData(
                        contract.getContractNumber(),
                        contract.getApplicationId(),
                        contract.getDocumentHash(),
                        contract.getSignatureProvider(),
                        contract.getSignatureMethod(),
                        contract.getSignatureTransactionId(),
                        contract.getSignatureEvidenceHash(),
                        contract.getSignedAt()
                )
        );
        activateMultiParty(contract, actorId, now);
        return new ContractConsentResult(contract, false);
    }

    /** Lưu durable SmartCA request trước khi gọi mạng; retry cùng key nhận lại đúng transaction. */
    @Transactional
    public ContractConsentResult beginDigitalSignature(
            String contractNumber,
            String idempotencyKey,
            String requestHash,
            SignLoanContractRequest request,
            String providerTransactionId,
            String documentId,
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
        LoanContractStatus previousStatus = contract.getStatus();
        contract.beginDigitalSignature(
                request.version(), request.documentHash(), providerTransactionId, documentId,
                idempotencyKey, requestHash, actorId, now);
        contractRepository.saveAndFlush(contract);
        saveHistory(contract, previousStatus, LoanContractStatus.SIGNING,
                "SMARTCA_SIGNATURE_REQUESTED", ActorType.BORROWER, actorId, now);
        return new ContractConsentResult(contract, false);
    }

    /** Chữ ký raw không lưu DB; chỉ lưu evidence hash và sinh receipt sau transition thành công. */
    @Transactional
    public LoanContract completeDigitalSignature(
            String contractNumber,
            SignatureEvidence evidence,
            String documentId,
            String actorId
    ) {
        LoanContract contract = lockedContract(contractNumber);
        boolean alreadyCompleted = contract.getStatus() == LoanContractStatus.SIGNED
                || contract.getStatus() == LoanContractStatus.EFFECTIVE
                || contract.getStatus() == LoanContractStatus.COMPLETED;
        contract.completeDigitalSignature(
                evidence.providerTransactionId(), documentId, evidence.evidenceHash(), actorId, clock.instant());
        if (alreadyCompleted) {
            return contract;
        }
        Instant now = clock.instant();
        recordBorrowerPartySignature(
                contract, contract.getConsentIdempotencyKey(), contract.getConsentRequestHash(), actorId, now);
        contractRepository.saveAndFlush(contract);
        createSignedReceiptAndEvent(contract, LoanContractStatus.SIGNING, actorId, now);
        activateMultiParty(contract, actorId, now);
        return contract;
    }

    @Transactional
    public LoanContract resetRejectedDigitalSignature(String contractNumber, String actorId) {
        LoanContract contract = lockedContract(contractNumber);
        LoanContractStatus previous = contract.getStatus();
        contract.resetRejectedDigitalSignature(actorId, clock.instant());
        if (previous == LoanContractStatus.SIGNING) {
            contractRepository.saveAndFlush(contract);
            LoanContractStatus pendingStatus = contract.isMultiParty()
                    ? LoanContractStatus.PENDING_BORROWER_SIGNATURE
                    : LoanContractStatus.PENDING_SIGNATURE;
            saveHistory(contract, LoanContractStatus.SIGNING, pendingStatus,
                    "SMARTCA_SIGNATURE_REJECTED", ActorType.BORROWER, actorId, clock.instant());
        }
        return contract;
    }

    private void createSignedReceiptAndEvent(
            LoanContract contract,
            LoanContractStatus fromStatus,
            String actorId,
            Instant now
    ) {
        LoanContractDocument signablePdf = contractDocumentRepository
                .findByContractIdAndArtifactType(contract.getId(), ContractPdfArtifactType.SIGNABLE)
                .orElse(null);
        if (signablePdf != null) {
            LoanApplication application = applicationRepository.findById(contract.getApplicationId())
                    .orElseThrow(() -> new ResourceNotFoundException(
                            "Loan Application", "id", contract.getApplicationId()));
            ScheduleCalculationSnapshot schedule = scheduleRepository.findById(contract.getCalculationSnapshotId())
                    .orElseThrow(() -> new ResourceNotFoundException(
                            "Schedule Calculation Snapshot", "id", contract.getCalculationSnapshotId()));
            ContractPdfArtifact receipt = signedReceipt(
                    contract, application, schedule, signablePdf.getContentHash());
            contractDocumentRepository.saveAndFlush(LoanContractDocument.create(
                    contract.getId(), receipt.artifactType(), receipt.documentVersion(),
                    receipt.contentHash(), receipt.content(), now));
        }
        saveHistory(contract, fromStatus, LoanContractStatus.SIGNED,
                "CONTRACT_SIGNED", ActorType.BORROWER, actorId, now);
        outboxService.record(
                "LoanContract", contract.getContractNumber(), "LoanContractSigned", 1,
                new LoanContractSignedEventData(
                        contract.getContractNumber(), contract.getApplicationId(), contract.getDocumentHash(),
                        contract.getSignatureProvider(), contract.getSignatureMethod(),
                        contract.getSignatureTransactionId(), contract.getSignatureEvidenceHash(),
                        contract.getSignedAt()));
    }

    private ContractPdfArtifact signedReceipt(
            LoanContract contract,
            LoanApplication application,
            ScheduleCalculationSnapshot schedule,
            String signedDocumentHash
    ) {
        if (!contract.isMultiParty()) {
            return pdfRenderer.renderSignedReceipt(contract, application, schedule, signedDocumentHash);
        }
        return pdfRenderer.renderSignedReceipt(
                contract, application, schedule, signedDocumentHash,
                partyRepository.findByContractIdOrderByPartyTypeAscCommitmentIdAsc(contract.getId()));
    }

    private void recordBorrowerPartySignature(
            LoanContract contract,
            String idempotencyKey,
            String requestHash,
            String actorId,
            Instant now
    ) {
        if (!contract.isMultiParty()) {
            return;
        }
        List<ContractParty> parties = partyRepository.findForUpdate(
                contract.getId(), ContractPartyType.BORROWER, actorId);
        if (parties.size() != 1) {
            throw LoanBusinessException.conflict(
                    "BORROWER_CONTRACT_PARTY_MISSING",
                    "Không tìm thấy đúng một bên vay trong hợp đồng nhiều bên");
        }
        if (contract.getSignatureProvider()
                == com.finora.loan.domain.contract.SignatureProviderType.VNPT_SMART_CA) {
            parties.getFirst().recordCompletedDigitalSignature(
                    contract.getSignatureTransactionId(), contract.getSignatureDocumentId(),
                    contract.getSignatureRequestedAt(), contract.getSignatureEvidenceHash(),
                    idempotencyKey, requestHash, actorId, now);
        } else {
            parties.getFirst().recordSignature(
                    contract.getSignatureMethod(), contract.getSignatureProvider(),
                    contract.getSignatureTransactionId(), contract.getSignatureEvidenceHash(),
                    idempotencyKey, requestHash, actorId, now);
        }
        partyRepository.saveAndFlush(parties.getFirst());
    }

    private void activateMultiParty(LoanContract contract, String actorId, Instant now) {
        if (!contract.isMultiParty()) {
            return;
        }
        contract.activateAfterAllSignatures(actorId, now);
        contractRepository.saveAndFlush(contract);
        saveHistory(contract, LoanContractStatus.SIGNED, LoanContractStatus.EFFECTIVE,
                "ALL_PARTIES_SIGNED", ActorType.SYSTEM, "SYSTEM-CONTRACT-ACTIVATION", now);
        LoanApplication application = applicationRepository.findById(contract.getApplicationId())
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Loan Application", "id", contract.getApplicationId()));
        LoanContractDocument receipt = contractDocumentRepository
                .findByContractIdAndArtifactType(contract.getId(), ContractPdfArtifactType.SIGNED_RECEIPT)
                .orElseThrow(() -> LoanBusinessException.conflict(
                        "SIGNED_RECEIPT_MISSING", "Không tìm thấy receipt sau khi đủ chữ ký"));
        outboxService.recordForPublication(
                "LoanContract", contract.getContractNumber(), "LoanContractActivated", 1,
                new LoanContractActivatedEventData(
                        contract.getApplicationId(), application.getApplicationNumber(),
                        contract.getContractNumber(), application.getInvestmentListingId(),
                        contract.getDocumentHash(), receipt.getContentHash(),
                        application.getAllocationVersion(), application.getAllocationHash(), now));
        disbursementSagaService.start(contract, application,
                partyRepository.findByContractIdOrderByPartyTypeAscCommitmentIdAsc(contract.getId()), now);
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
        LoanContractStatus previousStatus = contract.getStatus();
        contract.decline(request.version(), request.reasonCode(), request.reasonDetail(),
                idempotencyKey, requestHash, actorId, now);
        contractRepository.saveAndFlush(contract);
        saveHistory(contract, previousStatus, LoanContractStatus.DECLINED,
                request.reasonCode().name(), ActorType.BORROWER, actorId, now);
        recordClosedWithoutSignature(contract, request.reasonCode().name(), now);
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
        LoanContractStatus fromStatus = contract.getStatus();
        if (!contract.expireIfDue(now)) {
            return false;
        }
        contractRepository.saveAndFlush(contract);
        saveHistory(contract, fromStatus, LoanContractStatus.EXPIRED,
                "SIGNATURE_WINDOW_EXPIRED", ActorType.SYSTEM, "SYSTEM", now);
        recordClosedWithoutSignature(contract, "SIGNATURE_WINDOW_EXPIRED", now);
        return true;
    }

    @Transactional(readOnly = true)
    public List<Long> dueIds() {
        return contractRepository.findDueIds(
                List.of(
                        LoanContractStatus.PENDING_LENDER_SIGNATURES,
                        LoanContractStatus.PENDING_BORROWER_SIGNATURE,
                        LoanContractStatus.PENDING_SIGNATURE,
                        LoanContractStatus.SIGNING),
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
        recordClosedWithoutSignature(contract, "SIGNATURE_WINDOW_EXPIRED", now);
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

    private void recordClosedWithoutSignature(LoanContract contract, String reasonCode, Instant now) {
        outboxService.record(
                "LoanContract",
                contract.getContractNumber(),
                contract.getStatus() == LoanContractStatus.DECLINED
                        ? "LoanContractDeclined"
                        : "LoanContractExpired",
                1,
                new LoanContractClosedWithoutSignatureEventData(
                        contract.getContractNumber(),
                        contract.getApplicationId(),
                        contract.getStatus(),
                        reasonCode,
                        now
                )
        );
    }
}
