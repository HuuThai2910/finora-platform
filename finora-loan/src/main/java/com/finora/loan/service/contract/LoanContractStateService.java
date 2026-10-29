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
import com.finora.loan.domain.contract.ContractPdfSignature;
import com.finora.loan.domain.contract.ContractPdfSignatureAbandonReason;
import com.finora.loan.domain.contract.ContractPdfSignatureSummary;
import com.finora.loan.domain.contract.LoanContract;
import com.finora.loan.domain.contract.LoanContractDocument;
import com.finora.loan.domain.contract.LoanContractStatus;
import com.finora.loan.domain.contract.LoanContractStatusHistory;
import com.finora.loan.domain.core.ScheduleCalculationSnapshot;
import com.finora.loan.dto.contract.request.DeclineLoanContractRequest;
import com.finora.loan.dto.contract.request.SignLoanContractRequest;
import com.finora.loan.exception.LoanBusinessException;
import com.finora.loan.integration.signature.DigestSignatureCommand;
import com.finora.loan.integration.signature.SignatureEvidence;
import com.finora.loan.integration.signature.SignatureProvider;
import com.finora.loan.integration.signature.SignatureSubmissionStatus;
import com.finora.loan.integration.signature.SigningCertificate;
import com.finora.common.document.pades.EmbeddedPadesSignature;
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
import com.finora.loan.service.funding.LoanFundingCancellationService;
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
    private final LoanFundingCancellationService fundingCancellationService;
    private final ContractPdfSignatureService pdfSignatureService;
    private final SignatureProvider signatureProvider;

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
        verifyPdfConsent(contract, signablePdf, request.pdfDocumentHash());
        String pdfHash = signablePdf == null ? contract.getDocumentHash() : signablePdf.getContentHash();
        return new SignaturePreparation(
                contract.getContractNumber(), contract.getBorrowerId(), contract.getDocumentHash(), pdfHash,
                contract.isPadesSigned());
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
        verifyPdfConsent(contract, signablePdf, request.pdfDocumentHash());
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
        verifyPdfConsent(contract, signablePdf, request.pdfDocumentHash());
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

    /**
     * Borrower ký PAdES với provider cục bộ (MOCK): kiểm consent, nhúng chữ ký cuối của chuỗi, lập trang biên nhận,
     * history, outbox và kích hoạt Contract trong cùng transaction đang khóa Contract.
     */
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
        LoanContractDocument signablePdf = contractDocumentRepository
                .findByContractIdAndArtifactType(contract.getId(), ContractPdfArtifactType.SIGNABLE)
                .orElse(null);
        verifyPdfConsent(contract, signablePdf, request.pdfDocumentHash());
        // Kiểm owner/version/hash/trạng thái trước khi dựng PDF để request sai không tốn công ký.
        contract.validateSignature(request.version(), request.documentHash(), actorId, now);
        LoanContractStatus previousStatus = contract.getStatus();
        ContractPdfSignature signature = pdfSignatureService.signSynchronously(
                contract, borrowerSigner(actorId, certificate, idempotencyKey, requestHash), now);
        contract.sign(request.version(), request.documentHash(), signature.getMethod(), signature.getProvider(),
                signature.getProviderTransactionId(), signature.getEvidenceHash(),
                idempotencyKey, requestHash, actorId, now);
        recordBorrowerPartySignature(contract, idempotencyKey, requestHash, actorId, now);
        contractRepository.saveAndFlush(contract);
        createSignedReceiptAndEvent(contract, previousStatus, actorId, now);
        activateMultiParty(contract, actorId, now);
        return new ContractConsentResult(contract, false);
    }

    /** SmartCA: lưu lần ký PREPARED và chuyển Contract sang SIGNING trước khi gửi digest cho VNPT. */
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
        LoanContractDocument signablePdf = contractDocumentRepository
                .findByContractIdAndArtifactType(contract.getId(), ContractPdfArtifactType.SIGNABLE)
                .orElse(null);
        verifyPdfConsent(contract, signablePdf, request.pdfDocumentHash());
        contract.validateSignature(request.version(), request.documentHash(), actorId, now);
        LoanContractStatus previousStatus = contract.getStatus();
        ContractPdfSignature prepared = pdfSignatureService.prepare(
                contract, borrowerSigner(actorId, certificate, idempotencyKey, requestHash), now);
        contract.beginDigitalSignature(
                request.version(), request.documentHash(), prepared.getProviderTransactionId(),
                prepared.getProviderDocumentId(), idempotencyKey, requestHash, actorId, now);
        contractRepository.saveAndFlush(contract);
        saveHistory(contract, previousStatus, LoanContractStatus.SIGNING,
                "SMARTCA_SIGNATURE_REQUESTED", ActorType.BORROWER, actorId, now);
        return new PadesBorrowerStart(
                new ContractConsentResult(contract, false), prepared.getId(),
                pdfSignatureService.command(contract, prepared));
    }

    /** Lần ký PAdES đang chờ của borrower, khớp đúng transaction SmartCA đã ghi trên Contract. */
    @Transactional(readOnly = true)
    public PendingPadesSignature pendingBorrowerSignature(String contractNumber) {
        LoanContract contract = contractRepository.findByContractNumber(contractNumber)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Loan Contract", "contractNumber", contractNumber));
        ContractPdfSignature pending = pdfSignatureService.inFlight(contract.getId())
                .filter(signature -> signature.getSignerType() == ContractPartyType.BORROWER
                        && signature.getProviderTransactionId().equals(contract.getSignatureTransactionId()))
                .orElseThrow(() -> LoanBusinessException.conflict(
                        "BORROWER_SIGNATURE_STATE_MISMATCH", "Không tìm thấy phiên ký PDF đang chờ của người vay"));
        return new PendingPadesSignature(
                pending.getId(), contract.getSignatureTransactionId(), contract.getSignatureDocumentId(),
                pdfSignatureService.command(contract, pending));
    }

    /**
     * Áp kết quả SmartCA cho chữ ký cuối của borrower. Hoàn tất thì nhúng CMS rồi đi tiếp như luồng ký cũ (receipt,
     * event, kích hoạt); bị từ chối/quá hạn/chữ ký sai thì hủy lần ký và trả Contract về chờ borrower ký lại.
     */
    @Transactional
    public PadesBorrowerOutcome applyPadesSubmission(
            String contractNumber,
            Long signatureId,
            SignatureSubmissionStatus status,
            String signatureValue,
            boolean sessionExpired
    ) {
        LoanContract contract = lockedContract(contractNumber);
        ContractPdfSignature signature = pdfSignatureService.find(signatureId);
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
        ContractPdfSignatureService.PadesCompletion completion = pdfSignatureService.complete(
                contract, signature, signatureValue, now);
        if (!completion.signed()) {
            if (contract.expireDigitalSignatureSession(now)) {
                contractRepository.saveAndFlush(contract);
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
        contractRepository.saveAndFlush(contract);
        createSignedReceiptAndEvent(contract, LoanContractStatus.SIGNING, borrowerId, now);
        activateMultiParty(contract, borrowerId, now);
        return new PadesBorrowerOutcome(contract, false);
    }

    /** Phiên PDF đang chờ không có Contract SIGNING tương ứng (đã hết hạn/đóng) thì được hủy để giải phóng lượt ký. */
    @Transactional
    public void abandonOrphanedPadesSignature(String contractNumber) {
        LoanContract contract = lockedContract(contractNumber);
        if (contract.getStatus() == LoanContractStatus.SIGNING) {
            return;
        }
        pdfSignatureService.inFlight(contract.getId())
                .filter(signature -> signature.getSignerType() == ContractPartyType.BORROWER)
                .ifPresent(signature -> pdfSignatureService.abandon(
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
        pdfSignatureService.abandon(signature, reason, now);
        if (contract.expireDigitalSignatureSession(now)) {
            contractRepository.saveAndFlush(contract);
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
        LoanContractDocument signablePdf = contractDocumentRepository
                .findByContractIdAndArtifactType(contract.getId(), ContractPdfArtifactType.SIGNABLE)
                .orElse(null);
        if (contract.isPadesSigned()) {
            createPadesReceipt(contract, now);
        } else if (signablePdf != null) {
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

    /**
     * Sau chữ ký cuối của borrower: công bố SIGNED_PDF và lập trang biên nhận tách rời (Q9, D45). Dữ liệu biên nhận lấy
     * từ việc kiểm lại tệp đã ký; metadata DB chỉ dùng để gắn vai trò/mã bên ký và phải khớp chứng thư đã kiểm.
     */
    private void createPadesReceipt(LoanContract contract, Instant now) {
        ContractPdfSignatureService.PublishedPadesDocument published = pdfSignatureService.publishFinal(contract, now);
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
        LoanApplication application = applicationRepository.findById(contract.getApplicationId())
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Loan Application", "id", contract.getApplicationId()));
        ContractPdfArtifact receipt = pdfRenderer.renderSignatureReceipt(new ContractSignatureReceipt(
                contract.getContractNumber(), application.getApplicationNumber(),
                published.signable().getContentHash(), published.signable().getDocumentVersion(),
                published.signedPdf().getContentHash(), published.signedPdf().getContentLength(), now, rows));
        contractDocumentRepository.saveAndFlush(LoanContractDocument.create(
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
                        application.getAllocationVersion(), application.getAllocationHash(), now,
                        application.getBorrowerId()));
        disbursementSagaService.start(contract, application,
                partyRepository.findByContractIdOrderByPartyTypeAscCommitmentIdAsc(contract.getId()), now);
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
                ? pdfSignatureService.acceptsConsentHash(contract, signablePdf.getContentHash(), expectedPdfHash)
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
        abandonPdfSignatureOnClose(contract, now);
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
        abandonPdfSignatureOnClose(contract, now);
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
        // B49: history ghi đúng trạng thái trước khi hết hạn (PENDING_BORROWER_SIGNATURE/SIGNING với hợp đồng nhiều
        // bên), không mặc định PENDING_SIGNATURE của luồng một bên cũ.
        LoanContractStatus fromStatus = contract.getStatus();
        if (!contract.expireIfDue(now)) {
            return false;
        }
        contractRepository.saveAndFlush(contract);
        abandonPdfSignatureOnClose(contract, now);
        saveHistory(contract, fromStatus, LoanContractStatus.EXPIRED,
                "SIGNATURE_WINDOW_EXPIRED", ActorType.SYSTEM, "SYSTEM", now);
        recordClosedWithoutSignature(contract, "SIGNATURE_WINDOW_EXPIRED", now);
        return true;
    }

    /** Hợp đồng đóng khi còn phiên ký PDF đang chờ: hủy phiên để chữ ký về muộn không được nhúng vào hợp đồng đã đóng. */
    private void abandonPdfSignatureOnClose(LoanContract contract, Instant now) {
        if (contract.isPadesSigned()) {
            pdfSignatureService.abandonInFlight(
                    contract.getId(), ContractPdfSignatureAbandonReason.CONTRACT_CLOSED, now);
        }
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
        if (contract.isMultiParty()) {
            // Contract nhiều bên chỉ được lập sau LoanFullyFunded: tiền nhà đầu tư đang bị giữ theo allocation
            // đã khóa, nên phải phát bồi hoàn trong cùng transaction đóng Contract để không khóa tiền vô hạn.
            fundingCancellationService.cancel(
                    contract.getApplicationId(),
                    contract.getContractNumber(),
                    contract.getStatus() == LoanContractStatus.DECLINED
                            ? LoanFundingCancellationService.CONTRACT_DECLINED
                            : LoanFundingCancellationService.CONTRACT_EXPIRED,
                    now);
        }
    }

    /** Kết quả mở phiên ký PAdES của borrower; {@code command == null} khi request là bản lặp hoặc Contract vừa hết hạn. */
    public record PadesBorrowerStart(ContractConsentResult result, Long signatureId, DigestSignatureCommand command) {
    }

    public record PendingPadesSignature(
            Long signatureId,
            String transactionId,
            String documentId,
            DigestSignatureCommand command
    ) {
    }

    /** {@code invalidSignature}: provider trả chữ ký không khớp chứng thư, lần ký đã bị hủy và đã commit. */
    public record PadesBorrowerOutcome(LoanContract contract, boolean invalidSignature) {
    }
}
