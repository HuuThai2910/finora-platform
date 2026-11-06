package com.finora.loan.service.contract.impl;

import com.finora.common.document.pades.EmbeddedPadesSignature;
import com.finora.common.exception.ResourceNotFoundException;
import com.finora.common.logging.TraceContext;
import com.finora.loan.config.LoanContractProperties;
import com.finora.loan.domain.application.entity.LoanApplication;
import com.finora.loan.domain.application.enums.ActorType;
import com.finora.loan.domain.contract.entity.ContractParty;
import com.finora.loan.domain.contract.entity.ContractPdfSignature;
import com.finora.loan.domain.contract.entity.LoanContract;
import com.finora.loan.domain.contract.entity.LoanContractDocument;
import com.finora.loan.domain.contract.entity.LoanContractStatusHistory;
import com.finora.loan.domain.contract.enums.ConsentAction;
import com.finora.loan.domain.contract.enums.ContractPartyStatus;
import com.finora.loan.domain.contract.enums.ContractPartyType;
import com.finora.loan.domain.contract.enums.ContractPdfArtifactType;
import com.finora.loan.domain.contract.enums.ContractPdfSignatureAbandonReason;
import com.finora.loan.domain.contract.enums.LoanContractStatus;
import com.finora.loan.domain.contract.model.ContractPdfSignatureSummary;
import com.finora.loan.domain.core.entity.ScheduleCalculationSnapshot;
import com.finora.loan.dto.contract.request.DeclineLoanContractRequest;
import com.finora.loan.dto.contract.request.SignLoanContractRequest;
import com.finora.loan.exception.LoanBusinessException;
import com.finora.loan.integration.signature.client.SignatureProvider;
import com.finora.loan.integration.signature.contract.SignatureEvidence;
import com.finora.loan.integration.signature.contract.SignatureSubmissionStatus;
import com.finora.loan.integration.signature.contract.SigningCertificate;
import com.finora.loan.messaging.event.LoanContractActivatedEventData;
import com.finora.loan.messaging.event.LoanContractClosedWithoutSignatureEventData;
import com.finora.loan.messaging.event.LoanContractSignedEventData;
import com.finora.loan.repository.application.LoanApplicationRepository;
import com.finora.loan.repository.contract.ContractPartyRepository;
import com.finora.loan.repository.contract.LoanContractDocumentRepository;
import com.finora.loan.repository.contract.LoanContractRepository;
import com.finora.loan.repository.contract.LoanContractStatusHistoryRepository;
import com.finora.loan.repository.core.ScheduleCalculationSnapshotRepository;
import com.finora.loan.service.contract.ContractConsentResult;
import com.finora.loan.service.contract.ContractPdfArtifact;
import com.finora.loan.service.contract.ContractPdfRenderer;
import com.finora.loan.service.contract.ContractPdfSignatureService;
import com.finora.loan.service.contract.ContractSignatureReceipt;
import com.finora.loan.service.contract.LoanContractStateService;
import com.finora.loan.service.contract.SignaturePreparation;
import com.finora.loan.service.disbursement.DisbursementSagaService;
import com.finora.loan.service.funding.LoanFundingCancellationService;
import com.finora.loan.service.outbox.OutboxService;
import com.finora.loan.util.HashingService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.List;

/**
 * Các bước transaction của vòng đời hợp đồng phía người vay: ký (click-wrap, SmartCA, PAdES), từ chối, hết hạn và
 * đối soát idempotency; nhà đầu tư từ chối cũng đi qua đây vì đóng cả Contract.
 *
 * <p>Mỗi bước khóa dòng Contract, ghi history và outbox trong cùng transaction; lời gọi provider chữ ký nằm ở caller,
 * ngoài transaction.</p>
 */
@Service
@RequiredArgsConstructor
public class LoanContractStateServiceImpl implements LoanContractStateService {

    private final LoanContractRepository loanContractRepository;
    private final ContractPartyRepository contractPartyRepository;
    private final LoanContractDocumentRepository loanContractDocumentRepository;
    private final LoanContractStatusHistoryRepository loanContractStatusHistoryRepository;
    private final LoanApplicationRepository loanApplicationRepository;
    private final ScheduleCalculationSnapshotRepository scheduleCalculationSnapshotRepository;
    private final ContractPdfRenderer contractPdfRenderer;
    private final HashingService hashingService;
    private final LoanContractProperties loanContractProperties;
    private final Clock clock;
    private final OutboxService outboxService;
    private final DisbursementSagaService disbursementSagaService;
    private final LoanFundingCancellationService loanFundingCancellationService;
    private final ContractPdfSignatureService contractPdfSignatureService;
    private final SignatureProvider signatureProvider;

    @Override
    @Transactional(readOnly = true)
    public SignaturePreparation prepareSignature(
            String contractNumber,
            SignLoanContractRequest request,
            String actorId
    ) {
        LoanContract contract = loanContractRepository.findByContractNumber(contractNumber)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Loan Contract", "contractNumber", contractNumber));
        contract.validateSignature(request.version(), request.documentHash(), actorId, clock.instant());
        LoanContractDocument signablePdf = loanContractDocumentRepository
                .findByContractIdAndArtifactType(contract.getId(), ContractPdfArtifactType.SIGNABLE)
                .orElse(null);
        verifyPdfConsent(contract, signablePdf, request.pdfDocumentHash());
        String pdfHash = signablePdf == null ? contract.getDocumentHash() : signablePdf.getContentHash();
        return new SignaturePreparation(
                contract.getContractNumber(), contract.getBorrowerId(), contract.getDocumentHash(), pdfHash,
                contract.isPadesSigned());
    }

    @Override
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
        LoanContractDocument signablePdf = loanContractDocumentRepository
                .findByContractIdAndArtifactType(contract.getId(), ContractPdfArtifactType.SIGNABLE)
                .orElse(null);
        verifyPdfConsent(contract, signablePdf, request.pdfDocumentHash());
        LoanContractStatus previousStatus = contract.getStatus();
        contract.sign(request.version(), request.documentHash(), evidence.method(), evidence.provider(),
                evidence.providerTransactionId(), evidence.evidenceHash(),
                idempotencyKey, requestHash, actorId, now);
        recordBorrowerPartySignature(contract, idempotencyKey, requestHash, actorId, now);
        loanContractRepository.saveAndFlush(contract);
        if (signablePdf != null) {
            LoanApplication application = loanApplicationRepository.findById(contract.getApplicationId())
                    .orElseThrow(() -> new ResourceNotFoundException(
                            "Loan Application", "id", contract.getApplicationId()));
            ScheduleCalculationSnapshot schedule = scheduleCalculationSnapshotRepository
                    .findById(contract.getCalculationSnapshotId())
                    .orElseThrow(() -> new ResourceNotFoundException(
                            "Schedule Calculation Snapshot", "id", contract.getCalculationSnapshotId()));
            ContractPdfArtifact receipt = signedReceipt(
                    contract, application, schedule, signablePdf.getContentHash());
            loanContractDocumentRepository.saveAndFlush(LoanContractDocument.create(
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

    @Override
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
        LoanContractDocument signablePdf = loanContractDocumentRepository
                .findByContractIdAndArtifactType(contract.getId(), ContractPdfArtifactType.SIGNABLE)
                .orElse(null);
        verifyPdfConsent(contract, signablePdf, request.pdfDocumentHash());
        LoanContractStatus previousStatus = contract.getStatus();
        contract.beginDigitalSignature(
                request.version(), request.documentHash(), providerTransactionId, documentId,
                idempotencyKey, requestHash, actorId, now);
        loanContractRepository.saveAndFlush(contract);
        saveHistory(contract, previousStatus, LoanContractStatus.SIGNING,
                "SMARTCA_SIGNATURE_REQUESTED", ActorType.BORROWER, actorId, now);
        return new ContractConsentResult(contract, false);
    }

    @Override
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
        loanContractRepository.saveAndFlush(contract);
        createSignedReceiptAndEvent(contract, LoanContractStatus.SIGNING, actorId, now);
        activateMultiParty(contract, actorId, now);
        return contract;
    }

    @Override
    @Transactional
    public LoanContract resetRejectedDigitalSignature(String contractNumber, String actorId) {
        LoanContract contract = lockedContract(contractNumber);
        LoanContractStatus previousStatus = contract.getStatus();
        contract.resetRejectedDigitalSignature(actorId, clock.instant());
        if (previousStatus == LoanContractStatus.SIGNING) {
            loanContractRepository.saveAndFlush(contract);
            LoanContractStatus pendingStatus = contract.isMultiParty()
                    ? LoanContractStatus.PENDING_BORROWER_SIGNATURE
                    : LoanContractStatus.PENDING_SIGNATURE;
            saveHistory(contract, LoanContractStatus.SIGNING, pendingStatus,
                    "SMARTCA_SIGNATURE_REJECTED", ActorType.BORROWER, actorId, clock.instant());
        }
        return contract;
    }

    @Override
    @Transactional
    public ContractConsentResult signPades(
            String contractNumber,
            String idempotencyKey,
            String requestHash,
            SignLoanContractRequest request,
            SigningCertificate certificate,
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
        LoanContractDocument signablePdf = loanContractDocumentRepository
                .findByContractIdAndArtifactType(contract.getId(), ContractPdfArtifactType.SIGNABLE)
                .orElse(null);
        verifyPdfConsent(contract, signablePdf, request.pdfDocumentHash());
        // Kiểm owner/version/hash/trạng thái trước khi dựng PDF để request sai không tốn công ký.
        contract.validateSignature(request.version(), request.documentHash(), actorId, now);
        LoanContractStatus previousStatus = contract.getStatus();
        ContractPdfSignature signature = contractPdfSignatureService.signSynchronously(
                contract, borrowerSigner(actorId, certificate, idempotencyKey, requestHash), now);
        contract.sign(request.version(), request.documentHash(), signature.getMethod(), signature.getProvider(),
                signature.getProviderTransactionId(), signature.getEvidenceHash(),
                idempotencyKey, requestHash, actorId, now);
        recordBorrowerPartySignature(contract, idempotencyKey, requestHash, actorId, now);
        loanContractRepository.saveAndFlush(contract);
        createSignedReceiptAndEvent(contract, previousStatus, actorId, now);
        activateMultiParty(contract, actorId, now);
        return new ContractConsentResult(contract, false);
    }

    @Override
    @Transactional
    public PadesBorrowerStart beginPadesDigitalSignature(
            String contractNumber,
            String idempotencyKey,
            String requestHash,
            SignLoanContractRequest request,
            SigningCertificate certificate,
            String actorId
    ) {
        ContractConsentResult duplicate = duplicateConsent(
                contractNumber, idempotencyKey, requestHash, ConsentAction.SIGN, actorId);
        if (duplicate != null) {
            return new PadesBorrowerStart(duplicate, null, null);
        }
        LoanContract contract = lockedContract(contractNumber);
        contract.requireOwner(actorId);
        Instant now = clock.instant();
        if (expireDuringConsent(contract, now)) {
            return new PadesBorrowerStart(new ContractConsentResult(contract, true), null, null);
        }
        LoanContractDocument signablePdf = loanContractDocumentRepository
                .findByContractIdAndArtifactType(contract.getId(), ContractPdfArtifactType.SIGNABLE)
                .orElse(null);
        verifyPdfConsent(contract, signablePdf, request.pdfDocumentHash());
        contract.validateSignature(request.version(), request.documentHash(), actorId, now);
        LoanContractStatus previousStatus = contract.getStatus();
        ContractPdfSignature prepared = contractPdfSignatureService.prepare(
                contract, borrowerSigner(actorId, certificate, idempotencyKey, requestHash), now);
        contract.beginDigitalSignature(
                request.version(), request.documentHash(), prepared.getProviderTransactionId(),
                prepared.getProviderDocumentId(), idempotencyKey, requestHash, actorId, now);
        loanContractRepository.saveAndFlush(contract);
        saveHistory(contract, previousStatus, LoanContractStatus.SIGNING,
                "SMARTCA_SIGNATURE_REQUESTED", ActorType.BORROWER, actorId, now);
        return new PadesBorrowerStart(
                new ContractConsentResult(contract, false), prepared.getId(),
                contractPdfSignatureService.command(contract, prepared));
    }

    @Override
    @Transactional(readOnly = true)
    public PendingPadesSignature pendingBorrowerSignature(String contractNumber) {
        LoanContract contract = loanContractRepository.findByContractNumber(contractNumber)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Loan Contract", "contractNumber", contractNumber));
        ContractPdfSignature pending = contractPdfSignatureService.inFlight(contract.getId())
                .filter(signature -> signature.getSignerType() == ContractPartyType.BORROWER
                        && signature.getProviderTransactionId().equals(contract.getSignatureTransactionId()))
                .orElseThrow(() -> LoanBusinessException.conflict(
                        "BORROWER_SIGNATURE_STATE_MISMATCH", "Không tìm thấy phiên ký PDF đang chờ của người vay"));
        return new PendingPadesSignature(
                pending.getId(), contract.getSignatureTransactionId(), contract.getSignatureDocumentId(),
                contractPdfSignatureService.command(contract, pending));
    }

    @Override
    @Transactional
    public PadesBorrowerOutcome applyPadesSubmission(
            String contractNumber,
            Long signatureId,
            SignatureSubmissionStatus status,
            String signatureValue,
            boolean sessionExpired
    ) {
        LoanContract contract = lockedContract(contractNumber);
        ContractPdfSignature signature = contractPdfSignatureService.find(signatureId);
        if (!signature.isPrepared() || contract.getStatus() != LoanContractStatus.SIGNING) {
            return new PadesBorrowerOutcome(contract, false);
        }
        Instant now = clock.instant();
        if (status == SignatureSubmissionStatus.REJECTED) {
            closeBorrowerSession(contract, signature, ContractPdfSignatureAbandonReason.PROVIDER_REJECTED,
                    "SMARTCA_SIGNATURE_REJECTED", ActorType.BORROWER, contract.getBorrowerId(), now);
            return new PadesBorrowerOutcome(contract, false);
        }
        if (status != SignatureSubmissionStatus.COMPLETED) {
            if (sessionExpired) {
                closeBorrowerSession(contract, signature, ContractPdfSignatureAbandonReason.SIGNING_SESSION_EXPIRED,
                        "SMARTCA_SIGNATURE_SESSION_EXPIRED", ActorType.SYSTEM, "SYSTEM", now);
            }
            return new PadesBorrowerOutcome(contract, false);
        }
        if (expireDuringConsent(contract, now)) {
            // Chữ ký về sau hạn ký: Contract hết hạn (phiên PDF bị hủy cùng lúc), không nhúng chữ ký vào hợp đồng.
            return new PadesBorrowerOutcome(contract, false);
        }
        ContractPdfSignatureService.PadesCompletion completion = contractPdfSignatureService.complete(
                contract, signature, signatureValue, now);
        if (!completion.signed()) {
            if (contract.expireDigitalSignatureSession(now)) {
                loanContractRepository.saveAndFlush(contract);
                saveHistory(contract, LoanContractStatus.SIGNING, LoanContractStatus.PENDING_BORROWER_SIGNATURE,
                        "SMARTCA_SIGNATURE_INVALID", ActorType.SYSTEM, "SYSTEM", now);
            }
            return new PadesBorrowerOutcome(contract, true);
        }
        String borrowerId = contract.getBorrowerId();
        contract.completeDigitalSignature(
                signature.getProviderTransactionId(), signature.getProviderDocumentId(),
                completion.signature().getEvidenceHash(), borrowerId, now);
        recordBorrowerPartySignature(
                contract, contract.getConsentIdempotencyKey(), contract.getConsentRequestHash(), borrowerId, now);
        loanContractRepository.saveAndFlush(contract);
        createSignedReceiptAndEvent(contract, LoanContractStatus.SIGNING, borrowerId, now);
        activateMultiParty(contract, borrowerId, now);
        return new PadesBorrowerOutcome(contract, false);
    }

    @Override
    @Transactional
    public void abandonOrphanedPadesSignature(String contractNumber) {
        LoanContract contract = lockedContract(contractNumber);
        if (contract.getStatus() == LoanContractStatus.SIGNING) {
            return;
        }
        contractPdfSignatureService.inFlight(contract.getId())
                .filter(signature -> signature.getSignerType() == ContractPartyType.BORROWER)
                .ifPresent(signature -> contractPdfSignatureService.abandon(
                        signature, ContractPdfSignatureAbandonReason.CONTRACT_CLOSED, clock.instant()));
    }

    private void closeBorrowerSession(
            LoanContract contract,
            ContractPdfSignature signature,
            ContractPdfSignatureAbandonReason reason,
            String historyReason,
            ActorType actorType,
            String actorId,
            Instant now
    ) {
        contractPdfSignatureService.abandon(signature, reason, now);
        if (contract.expireDigitalSignatureSession(now)) {
            loanContractRepository.saveAndFlush(contract);
            saveHistory(contract, LoanContractStatus.SIGNING, LoanContractStatus.PENDING_BORROWER_SIGNATURE,
                    historyReason, actorType, actorId, now);
        }
    }

    private ContractPdfSignatureService.PadesSignerRequest borrowerSigner(
            String borrowerId,
            SigningCertificate certificate,
            String idempotencyKey,
            String requestHash
    ) {
        return new ContractPdfSignatureService.PadesSignerRequest(
                ContractPartyType.BORROWER, borrowerId, certificate, signatureProvider.type(),
                signatureProvider.method(), idempotencyKey, requestHash);
    }

    private void createSignedReceiptAndEvent(
            LoanContract contract,
            LoanContractStatus fromStatus,
            String actorId,
            Instant now
    ) {
        LoanContractDocument signablePdf = loanContractDocumentRepository
                .findByContractIdAndArtifactType(contract.getId(), ContractPdfArtifactType.SIGNABLE)
                .orElse(null);
        if (contract.isPadesSigned()) {
            createPadesReceipt(contract, now);
        } else if (signablePdf != null) {
            LoanApplication application = loanApplicationRepository.findById(contract.getApplicationId())
                    .orElseThrow(() -> new ResourceNotFoundException(
                            "Loan Application", "id", contract.getApplicationId()));
            ScheduleCalculationSnapshot schedule = scheduleCalculationSnapshotRepository
                    .findById(contract.getCalculationSnapshotId())
                    .orElseThrow(() -> new ResourceNotFoundException(
                            "Schedule Calculation Snapshot", "id", contract.getCalculationSnapshotId()));
            ContractPdfArtifact receipt = signedReceipt(
                    contract, application, schedule, signablePdf.getContentHash());
            loanContractDocumentRepository.saveAndFlush(LoanContractDocument.create(
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

    /**
     * Sau chữ ký cuối của borrower: công bố SIGNED_PDF và lập trang biên nhận tách rời (Q9, D45). Dữ liệu biên nhận lấy
     * từ việc kiểm lại tệp đã ký; metadata DB chỉ dùng để gắn vai trò/mã bên ký và phải khớp chứng thư đã kiểm.
     */
    private void createPadesReceipt(LoanContract contract, Instant now) {
        ContractPdfSignatureService.PublishedPadesDocument published =
                contractPdfSignatureService.publishFinal(contract, now);
        List<EmbeddedPadesSignature> verified = published.verifiedSignatures();
        List<ContractPdfSignatureSummary> signatures = published.signatures();
        if (verified.size() != signatures.size()) {
            throw new IllegalStateException("Số chữ ký trong PDF không khớp chuỗi chữ ký đã lưu");
        }
        List<ContractSignatureReceipt.Row> rows = new java.util.ArrayList<>();
        for (int index = 0; index < verified.size(); index++) {
            EmbeddedPadesSignature embedded = verified.get(index);
            ContractPdfSignatureSummary summary = signatures.get(index);
            if (summary.sequenceNumber() != embedded.revision()
                    || !summary.certificateSha256().equals(embedded.certificateSha256())) {
                throw new IllegalStateException("Chữ ký trong PDF không khớp thứ tự hoặc chứng thư đã lưu");
            }
            rows.add(new ContractSignatureReceipt.Row(
                    embedded.revision(), summary.signerType(), summary.signerId(), summary.signingTime(),
                    summary.provider(), summary.method(), embedded.subjectCommonName(), embedded.serialHex(),
                    embedded.issuerCommonName(), embedded.certificateSha256(), embedded.revisionHash(),
                    embedded.coveredLength()));
        }
        LoanApplication application = loanApplicationRepository.findById(contract.getApplicationId())
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Loan Application", "id", contract.getApplicationId()));
        ContractPdfArtifact receipt = contractPdfRenderer.renderSignatureReceipt(new ContractSignatureReceipt(
                contract.getContractNumber(), application.getApplicationNumber(),
                published.signable().getContentHash(), published.signable().getDocumentVersion(),
                published.signedPdf().getContentHash(), published.signedPdf().getContentLength(), now, rows));
        loanContractDocumentRepository.saveAndFlush(LoanContractDocument.create(
                contract.getId(), receipt.artifactType(), receipt.documentVersion(),
                receipt.contentHash(), receipt.content(), now));
    }

    private ContractPdfArtifact signedReceipt(
            LoanContract contract,
            LoanApplication application,
            ScheduleCalculationSnapshot schedule,
            String signedDocumentHash
    ) {
        if (!contract.isMultiParty()) {
            return contractPdfRenderer.renderSignedReceipt(contract, application, schedule, signedDocumentHash);
        }
        return contractPdfRenderer.renderSignedReceipt(
                contract, application, schedule, signedDocumentHash,
                contractPartyRepository.findByContractIdOrderByPartyTypeAscCommitmentIdAsc(contract.getId()));
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
        List<ContractParty> parties = contractPartyRepository.findForUpdate(
                contract.getId(), ContractPartyType.BORROWER, actorId);
        if (parties.size() != 1) {
            throw LoanBusinessException.conflict(
                    "BORROWER_CONTRACT_PARTY_MISSING",
                    "Không tìm thấy đúng một bên vay trong hợp đồng nhiều bên");
        }
        if (contract.getSignatureProvider()
                == com.finora.loan.domain.contract.enums.SignatureProviderType.VNPT_SMART_CA) {
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
        contractPartyRepository.saveAndFlush(parties.getFirst());
    }

    private void activateMultiParty(LoanContract contract, String actorId, Instant now) {
        if (!contract.isMultiParty()) {
            return;
        }
        contract.activateAfterAllSignatures(actorId, now);
        loanContractRepository.saveAndFlush(contract);
        saveHistory(contract, LoanContractStatus.SIGNED, LoanContractStatus.EFFECTIVE,
                "ALL_PARTIES_SIGNED", ActorType.SYSTEM, "SYSTEM-CONTRACT-ACTIVATION", now);
        LoanApplication application = loanApplicationRepository.findById(contract.getApplicationId())
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Loan Application", "id", contract.getApplicationId()));
        LoanContractDocument receipt = loanContractDocumentRepository
                .findByContractIdAndArtifactType(contract.getId(), ContractPdfArtifactType.SIGNED_RECEIPT)
                .orElseThrow(() -> LoanBusinessException.conflict(
                        "SIGNED_RECEIPT_MISSING", "Không tìm thấy receipt sau khi đủ chữ ký"));
        outboxService.recordForPublication(
                "LoanContract", contract.getContractNumber(), "LoanContractActivated", 1,
                new LoanContractActivatedEventData(
                        contract.getApplicationId(), application.getApplicationNumber(),
                        contract.getContractNumber(), application.getInvestmentListingId(),
                        contract.getDocumentHash(), receipt.getContentHash(),
                        application.getAllocationVersion(), application.getAllocationHash(), now,
                        application.getBorrowerId()));
        disbursementSagaService.start(contract, application,
                contractPartyRepository.findByContractIdOrderByPartyTypeAscCommitmentIdAsc(contract.getId()), now);
    }

    /**
     * Borrower phải gửi đúng hash PDF đã xem. Với hợp đồng PAdES (D47) nhận hash SIGNABLE hoặc hash bản đầu chuỗi đang
     * có chữ ký bên cho vay: hai tệp cùng nội dung, bản sau chỉ thêm chữ ký ẩn.
     */
    private void verifyPdfConsent(LoanContract contract, LoanContractDocument signablePdf, String expectedPdfHash) {
        if (signablePdf == null) {
            return;
        }
        boolean matches = contract.isPadesSigned()
                ? contractPdfSignatureService.acceptsConsentHash(
                        contract, signablePdf.getContentHash(), expectedPdfHash)
                : expectedPdfHash != null && !expectedPdfHash.isBlank()
                        && signablePdf.getContentHash().equals(expectedPdfHash);
        if (!matches) {
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

    @Override
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
        loanContractRepository.saveAndFlush(contract);
        abandonPdfSignatureOnClose(contract, now);
        saveHistory(contract, previousStatus, LoanContractStatus.DECLINED,
                request.reasonCode().name(), ActorType.BORROWER, actorId, now);
        recordClosedWithoutSignature(contract, request.reasonCode().name(), now);
        return new ContractConsentResult(contract, false);
    }

    @Override
    @Transactional
    public ContractConsentResult declineByInvestor(
            String contractNumber,
            String idempotencyKey,
            String requestHash,
            DeclineLoanContractRequest request,
            String investorId
    ) {
        LoanContract duplicate = loanContractRepository.findByConsentIdempotencyKey(idempotencyKey).orElse(null);
        if (duplicate != null) {
            if (!duplicate.getContractNumber().equals(contractNumber)
                    || !investorId.equals(duplicate.getDeclinedBy())
                    || !duplicate.isSameConsent(idempotencyKey, requestHash, ConsentAction.DECLINE)) {
                throw LoanBusinessException.conflict(
                        "IDEMPOTENCY_KEY_REUSED", "Idempotency-Key đã được dùng cho một consent khác");
            }
            return new ContractConsentResult(duplicate, false);
        }
        if (request.reasonCode() != com.finora.loan.domain.contract.enums.ContractDeclineReasonCode.INVESTOR_CHANGED_MIND
                && request.reasonCode() != com.finora.loan.domain.contract.enums.ContractDeclineReasonCode.OTHER) {
            throw LoanBusinessException.badRequest(
                    "INVALID_INVESTOR_DECLINE_REASON", "Lý do từ chối không dành cho nhà đầu tư");
        }
        LoanContract contract = lockedContract(contractNumber);
        List<ContractParty> parties = contractPartyRepository.findForUpdate(
                contract.getId(), ContractPartyType.LENDER, investorId);
        if (parties.isEmpty()) {
            throw LoanBusinessException.forbidden(
                    "INVESTOR_CONTRACT_ACCESS_DENIED", "Bạn không có phần vốn trong hợp đồng này");
        }
        if (parties.stream().anyMatch(party -> party.getStatus() == ContractPartyStatus.SIGNED)) {
            throw LoanBusinessException.conflict(
                    "CONTRACT_PARTY_ALREADY_SIGNED", "Phần vốn đã ký nên không thể rút đơn phương");
        }
        Instant now = clock.instant();
        if (expireDuringConsent(contract, now)) {
            return new ContractConsentResult(contract, true);
        }
        LoanContractStatus previousStatus = contract.getStatus();
        contract.declineByInvestor(
                request.version(), request.reasonCode(), request.reasonDetail(), idempotencyKey,
                requestHash, investorId, now);
        parties.forEach(party -> party.decline(idempotencyKey, requestHash, investorId, now));
        contractPartyRepository.saveAllAndFlush(parties);
        loanContractRepository.saveAndFlush(contract);
        abandonPdfSignatureOnClose(contract, now);
        saveHistory(contract, previousStatus, LoanContractStatus.DECLINED,
                request.reasonCode().name(), ActorType.INVESTOR, investorId, now);
        recordClosedWithoutSignature(contract, request.reasonCode().name(), now);
        return new ContractConsentResult(contract, false);
    }

    @Override
    @Transactional
    public boolean expireOne(Long contractId) {
        LoanContract contract = loanContractRepository.findByIdForUpdate(contractId).orElse(null);
        if (contract == null) {
            return false;
        }
        Instant now = clock.instant();
        LoanContractStatus fromStatus = contract.getStatus();
        if (!contract.expireIfDue(now)) {
            return false;
        }
        loanContractRepository.saveAndFlush(contract);
        abandonPdfSignatureOnClose(contract, now);
        saveHistory(contract, fromStatus, LoanContractStatus.EXPIRED,
                "SIGNATURE_WINDOW_EXPIRED", ActorType.SYSTEM, "SYSTEM", now);
        recordClosedWithoutSignature(contract, "SIGNATURE_WINDOW_EXPIRED", now);
        return true;
    }

    @Override
    @Transactional(readOnly = true)
    public List<Long> dueIds() {
        return loanContractRepository.findDueIds(
                List.of(
                        LoanContractStatus.PENDING_LENDER_SIGNATURES,
                        LoanContractStatus.PENDING_BORROWER_SIGNATURE,
                        LoanContractStatus.PENDING_SIGNATURE,
                        LoanContractStatus.SIGNING),
                clock.instant(),
                PageRequest.of(0, loanContractProperties.expiryBatchSize())
        );
    }

    @Override
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
        LoanContract existing = loanContractRepository.findByConsentIdempotencyKey(idempotencyKey).orElse(null);
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
        // B49: history ghi đúng trạng thái trước khi hết hạn (PENDING_BORROWER_SIGNATURE/SIGNING với hợp đồng nhiều
        // bên), không mặc định PENDING_SIGNATURE của luồng một bên cũ.
        LoanContractStatus fromStatus = contract.getStatus();
        if (!contract.expireIfDue(now)) {
            return false;
        }
        loanContractRepository.saveAndFlush(contract);
        abandonPdfSignatureOnClose(contract, now);
        saveHistory(contract, fromStatus, LoanContractStatus.EXPIRED,
                "SIGNATURE_WINDOW_EXPIRED", ActorType.SYSTEM, "SYSTEM", now);
        recordClosedWithoutSignature(contract, "SIGNATURE_WINDOW_EXPIRED", now);
        return true;
    }

    /** Hợp đồng đóng khi còn phiên ký PDF đang chờ: hủy phiên để chữ ký về muộn không được nhúng vào hợp đồng đã đóng. */
    private void abandonPdfSignatureOnClose(LoanContract contract, Instant now) {
        if (contract.isPadesSigned()) {
            contractPdfSignatureService.abandonInFlight(
                    contract.getId(), ContractPdfSignatureAbandonReason.CONTRACT_CLOSED, now);
        }
    }

    private LoanContract lockedContract(String contractNumber) {
        return loanContractRepository.findByContractNumberForUpdate(contractNumber)
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
        loanContractStatusHistoryRepository.saveAndFlush(LoanContractStatusHistory.create(
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
        if (contract.isMultiParty()) {
            // Contract nhiều bên chỉ được lập sau LoanFullyFunded: tiền nhà đầu tư đang bị giữ theo allocation
            // đã khóa, nên phải phát bồi hoàn trong cùng transaction đóng Contract để không khóa tiền vô hạn.
            loanFundingCancellationService.cancel(
                    contract.getApplicationId(),
                    contract.getContractNumber(),
                    contract.getStatus() == LoanContractStatus.DECLINED
                            ? LoanFundingCancellationService.CONTRACT_DECLINED
                            : LoanFundingCancellationService.CONTRACT_EXPIRED,
                    now);
        }
    }
}
