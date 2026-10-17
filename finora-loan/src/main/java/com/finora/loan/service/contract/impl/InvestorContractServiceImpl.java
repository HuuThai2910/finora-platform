package com.finora.loan.service.contract.impl;

import com.finora.common.exception.ResourceNotFoundException;
import com.finora.common.logging.TraceContext;
import com.finora.common.security.SecurityUtils;
import com.finora.loan.domain.application.ActorType;
import com.finora.loan.domain.application.LoanApplication;
import com.finora.loan.domain.contract.ContractParty;
import com.finora.loan.domain.contract.ContractPartyStatus;
import com.finora.loan.domain.contract.ContractPartyType;
import com.finora.loan.domain.contract.ContractPdfArtifactType;
import com.finora.loan.domain.contract.ContractPdfSignature;
import com.finora.loan.domain.contract.ContractPdfSignatureAbandonReason;
import com.finora.loan.domain.contract.ContractPdfSignatureSummary;
import com.finora.loan.domain.contract.LoanContract;
import com.finora.loan.domain.contract.LoanContractDocument;
import com.finora.loan.domain.contract.LoanContractStatus;
import com.finora.loan.domain.contract.LoanContractStatusHistory;
import com.finora.loan.domain.contract.SignatureProviderType;
import com.finora.loan.dto.contract.request.SignLoanContractRequest;
import com.finora.loan.dto.contract.response.InvestorContractResponse;
import com.finora.loan.exception.LoanBusinessException;
import com.finora.loan.integration.signature.DigestSignatureCommand;
import com.finora.loan.integration.signature.SignatureCommand;
import com.finora.loan.integration.signature.SignatureProvider;
import com.finora.loan.integration.signature.SignatureSubmission;
import com.finora.loan.integration.signature.SignatureSubmissionStatus;
import com.finora.loan.integration.signature.SigningCertificate;
import com.finora.loan.integration.signature.SmartCaIntegrationException;
import com.finora.loan.messaging.event.BorrowerSignatureRequestedEventData;
import com.finora.loan.repository.application.LoanApplicationRepository;
import com.finora.loan.repository.contract.ContractPartyRepository;
import com.finora.loan.repository.contract.LoanContractDocumentRepository;
import com.finora.loan.repository.contract.LoanContractRepository;
import com.finora.loan.repository.contract.LoanContractStatusHistoryRepository;
import com.finora.loan.service.contract.ContractPdfSignatureService;
import com.finora.loan.service.contract.InvestorContractService;
import com.finora.loan.service.contract.LoanContractPdfContent;
import com.finora.loan.service.outbox.OutboxService;
import com.finora.loan.support.HashingService;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.LinkedHashMap;
import java.math.BigDecimal;
import java.util.function.Supplier;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

@Service
@RequiredArgsConstructor
@Slf4j
public class InvestorContractServiceImpl implements InvestorContractService {

    private final LoanContractRepository contractRepository;
    private final ContractPartyRepository partyRepository;
    private final LoanContractDocumentRepository documentRepository;
    private final LoanContractStatusHistoryRepository historyRepository;
    private final LoanApplicationRepository applicationRepository;
    private final SignatureProvider signatureProvider;
    private final ContractPdfSignatureService pdfSignatureService;
    private final HashingService hashingService;
    private final OutboxService outboxService;
    private final Clock clock;
    private final PlatformTransactionManager transactionManager;

    @Override
    @Transactional(readOnly = true)
    public List<InvestorContractResponse> listMine() {
        String investorId = SecurityUtils.getCurrentUserId();
        LinkedHashMap<Long, ContractParty> firstPartyByContract = new LinkedHashMap<>();
        partyRepository.findByPartyTypeAndPartyIdOrderByCreatedAtDesc(
                        ContractPartyType.LENDER, investorId)
                .forEach(party -> firstPartyByContract.putIfAbsent(party.getContractId(), party));
        return firstPartyByContract.entrySet().stream()
                .map(entry -> response(
                        contractRepository.findById(entry.getKey()).orElseThrow(), entry.getValue()))
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public InvestorContractResponse detail(String contractNumber) {
        String investorId = SecurityUtils.getCurrentUserId();
        LoanContract contract = contract(contractNumber);
        List<ContractParty> parties = lenderParties(contract, investorId);
        return response(contract, parties.getFirst());
    }

    @Override
    @Transactional(readOnly = true)
    public LoanContractPdfContent document(String contractNumber) {
        String investorId = SecurityUtils.getCurrentUserId();
        LoanContract contract = contract(contractNumber);
        lenderParties(contract, investorId);
        LoanContractDocument document = currentDocument(contract);
        return new LoanContractPdfContent(
                contractNumber + ".pdf", document.getContentType(), document.getContentHash(),
                document.contentCopy());
    }

    @Override
    @Transactional(readOnly = true)
    public LoanContractPdfContent signedDocument(String contractNumber) {
        String investorId = SecurityUtils.getCurrentUserId();
        LoanContract contract = contract(contractNumber);
        lenderParties(contract, investorId);
        ContractPdfSignature latest = pdfSignatureService.latestSigned(contract.getId())
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Loan Contract signed PDF", "contractNumber", contractNumber));
        return new LoanContractPdfContent(
                contractNumber + "-signed.pdf", LoanContractDocument.PDF_CONTENT_TYPE, latest.getContentHash(),
                latest.contentCopy());
    }

    @Override
    @Transactional(readOnly = true)
    public LoanContractPdfContent receipt(String contractNumber) {
        String investorId = SecurityUtils.getCurrentUserId();
        LoanContract contract = contract(contractNumber);
        lenderParties(contract, investorId);
        LoanContractDocument receipt = documentRepository.findByContractIdAndArtifactType(
                        contract.getId(), ContractPdfArtifactType.SIGNED_RECEIPT)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Loan Contract receipt", "contractNumber", contractNumber));
        return new LoanContractPdfContent(
                contractNumber + "-receipt.pdf", receipt.getContentType(), receipt.getContentHash(),
                receipt.contentCopy());
    }

    @Override
    public InvestorContractResponse sign(
            String contractNumber,
            String idempotencyKey,
            SignLoanContractRequest request
    ) {
        String investorId = SecurityUtils.getCurrentUserId();
        String normalizedKey = requireIdempotencyKey(idempotencyKey);
        String requestHash = hashingService.sha256(new InvestorConsentFingerprint(
                contractNumber, investorId, request));
        if (contract(contractNumber).isPadesSigned()) {
            return signPades(contractNumber, investorId, normalizedKey, requestHash, request);
        }
        InvestorSignatureContext context = transactionally(() -> prepareSignature(
                contractNumber, investorId, normalizedKey, requestHash, request));
        if (context.alreadySigned()) {
            return detail(contractNumber);
        }

        SignatureSubmission submission;
        if (context.alreadyStarted()) {
            submission = status(context.transactionId(), context.documentId());
            if (submission.status() == SignatureSubmissionStatus.NOT_FOUND) {
                submission = submit(context.command());
            }
        } else {
            submission = submit(context.command());
        }
        if (signatureProvider.type() == SignatureProviderType.MOCK
                && submission.status() != SignatureSubmissionStatus.COMPLETED) {
            throw LoanBusinessException.serviceUnavailable(
                    "INVESTOR_SIGNATURE_PENDING", "Provider mock chưa hoàn tất chữ ký nhà đầu tư");
        }
        SignatureSubmission result = submission;
        return transactionally(() -> applySubmission(context, result));
    }

    @Override
    public InvestorContractResponse refreshSignature(String contractNumber) {
        String investorId = SecurityUtils.getCurrentUserId();
        if (contract(contractNumber).isPadesSigned()) {
            return refreshPades(contractNumber, investorId);
        }
        InvestorSignatureContext context = transactionally(
                () -> existingSignature(contractNumber, investorId));
        if (context.alreadySigned()) {
            return detail(contractNumber);
        }
        SignatureSubmission submission = status(context.transactionId(), context.documentId());
        if (submission.status() == SignatureSubmissionStatus.NOT_FOUND) {
            submission = submit(context.command());
        }
        SignatureSubmission result = submission;
        return transactionally(() -> applySubmission(context, result));
    }

    /**
     * Worker chỉ hỏi trạng thái, không gửi lại yêu cầu ký (người dùng không có mặt để xác nhận một push mới). Lỗi
     * SmartCA tạm thời được bỏ qua tới lượt sau, trừ khi phiên đã quá hạn thì đóng phiên để giải phóng lượt ký.
     */
    @Override
    public void reconcilePadesSignature(String contractNumber, String investorId, boolean sessionExpired) {
        PadesLenderAttempt attempt;
        try {
            attempt = transactionally(() -> existingPadesSignature(contractNumber, investorId));
        } catch (LoanBusinessException inconsistent) {
            // Phiên PDF chờ mà phần vốn không còn SIGNING là trạng thái lệch; quá hạn thì đóng để không chặn bên khác.
            if (sessionExpired) {
                transactionally(() -> abandonOrphanedLenderSignature(contractNumber, investorId));
            }
            return;
        }
        if (attempt.completed()) {
            return;
        }
        SignatureSubmission submission;
        try {
            submission = signatureProvider.status(attempt.transactionId(), attempt.documentId());
        } catch (SmartCaIntegrationException exception) {
            if (!sessionExpired) {
                return;
            }
            submission = SignatureSubmission.pending(attempt.transactionId(), attempt.documentId(), "UNAVAILABLE");
        }
        SignatureSubmission result = submission;
        PadesApplyResult applied = transactionally(() -> applyPadesSubmission(
                contractNumber, investorId, attempt.signatureId(), result, sessionExpired));
        if (applied.invalidSignature()) {
            log.warn("SmartCA trả chữ ký không khớp chứng thư, đã hủy lần ký: contractNumber={}, signatureId={}",
                    contractNumber, attempt.signatureId());
        }
    }

    private InvestorContractResponse signPades(
            String contractNumber,
            String investorId,
            String normalizedKey,
            String requestHash,
            SignLoanContractRequest request
    ) {
        // Chứng thư lấy ngoài transaction: với SmartCA đây là lời gọi mạng, không được giữ khóa Contract khi chờ.
        SigningCertificate certificate = signingCertificate(investorId);
        if (signatureProvider.completesSynchronously()) {
            return transactionally(() -> signPadesSynchronously(
                    contractNumber, investorId, normalizedKey, requestHash, request, certificate));
        }
        PadesLenderAttempt attempt = transactionally(() -> beginPadesSignature(
                contractNumber, investorId, normalizedKey, requestHash, request, certificate));
        if (attempt.completed()) {
            return detail(contractNumber);
        }
        SignatureSubmission submission = attempt.resumed()
                ? status(attempt.transactionId(), attempt.documentId())
                : null;
        if (submission == null || submission.status() == SignatureSubmissionStatus.NOT_FOUND) {
            submission = submitDigest(attempt.command());
        }
        return finishPadesApply(contractNumber, investorId, attempt.signatureId(), submission);
    }

    private InvestorContractResponse refreshPades(String contractNumber, String investorId) {
        PadesLenderAttempt attempt = transactionally(() -> existingPadesSignature(contractNumber, investorId));
        if (attempt.completed()) {
            return detail(contractNumber);
        }
        SignatureSubmission submission = status(attempt.transactionId(), attempt.documentId());
        if (submission.status() == SignatureSubmissionStatus.NOT_FOUND) {
            submission = submitDigest(attempt.command());
        }
        return finishPadesApply(contractNumber, investorId, attempt.signatureId(), submission);
    }

    /** Lưu kết quả (kể cả việc hủy lần ký có chữ ký sai) trước, sau đó mới báo lỗi cho client. */
    private InvestorContractResponse finishPadesApply(
            String contractNumber,
            String investorId,
            Long signatureId,
            SignatureSubmission submission
    ) {
        PadesApplyResult applied = transactionally(() -> applyPadesSubmission(
                contractNumber, investorId, signatureId, submission, false));
        if (applied.invalidSignature()) {
            throw LoanBusinessException.serviceUnavailable(
                    "SIGNATURE_PROVIDER_INVALID_SIGNATURE",
                    "Chữ ký nhà cung cấp trả về không khớp chứng thư; vui lòng ký lại");
        }
        return applied.response();
    }

    /**
     * MOCK: chuẩn bị, ký bằng khóa demo, nhúng và ghi chữ ký bên cho vay trong một transaction đang khóa Contract.
     * Nhà đầu tư khác ký cùng lúc chỉ chờ khóa rồi ký tiếp trên bản mới, không nhận 409.
     */
    private InvestorContractResponse signPadesSynchronously(
            String contractNumber,
            String investorId,
            String normalizedKey,
            String requestHash,
            SignLoanContractRequest request,
            SigningCertificate certificate
    ) {
        LoanContract contract = lockedContract(contractNumber);
        List<ContractParty> parties = lenderPartiesForUpdate(contract, investorId);
        LenderRequestState state = lenderRequestState(contract, parties, investorId, normalizedKey, requestHash);
        if (state == LenderRequestState.ALREADY_SIGNED) {
            return response(contract, parties.getFirst());
        }
        if (state == LenderRequestState.STARTED) {
            throw LoanBusinessException.conflict(
                    "INVESTOR_SIGNATURE_STATE_MISMATCH", "Nhà đầu tư còn một phiên ký số đang chờ");
        }
        validateNewLenderSignature(contract, request);
        Instant now = clock.instant();
        ContractPdfSignature signature = pdfSignatureService.signSynchronously(
                contract, signerRequest(investorId, certificate, normalizedKey, requestHash), now);
        for (ContractParty party : parties) {
            party.signMock(
                    signature.getProviderTransactionId(), signature.getEvidenceHash(),
                    stablePartyIdempotencyKey(contractNumber, investorId, normalizedKey, party.getCommitmentId()),
                    requestHash, investorId, now);
        }
        partyRepository.saveAllAndFlush(parties);
        advanceContractIfAllLendersSigned(contract, signableDocument(contract), now);
        return response(contract, parties.getFirst());
    }

    /** SmartCA: lưu PREPARED và chuyển các phần vốn của nhà đầu tư sang SIGNING trước khi gửi hash đi. */
    private PadesLenderAttempt beginPadesSignature(
            String contractNumber,
            String investorId,
            String normalizedKey,
            String requestHash,
            SignLoanContractRequest request,
            SigningCertificate certificate
    ) {
        LoanContract contract = lockedContract(contractNumber);
        List<ContractParty> parties = lenderPartiesForUpdate(contract, investorId);
        LenderRequestState state = lenderRequestState(contract, parties, investorId, normalizedKey, requestHash);
        if (state == LenderRequestState.ALREADY_SIGNED) {
            return PadesLenderAttempt.alreadyCompleted();
        }
        if (state == LenderRequestState.STARTED) {
            return resumedAttempt(contract, parties, investorId);
        }
        validateNewLenderSignature(contract, request);
        Instant now = clock.instant();
        ContractPdfSignature prepared = pdfSignatureService.prepare(
                contract, signerRequest(investorId, certificate, normalizedKey, requestHash), now);
        for (ContractParty party : parties) {
            party.beginDigitalSignature(
                    prepared.getProviderTransactionId(), prepared.getProviderDocumentId(),
                    stablePartyIdempotencyKey(contractNumber, investorId, normalizedKey, party.getCommitmentId()),
                    requestHash, investorId, now);
        }
        partyRepository.saveAllAndFlush(parties);
        return PadesLenderAttempt.of(prepared, pdfSignatureService.command(contract, prepared), false);
    }

    private PadesLenderAttempt existingPadesSignature(String contractNumber, String investorId) {
        LoanContract contract = lockedContract(contractNumber);
        List<ContractParty> parties = lenderPartiesForUpdate(contract, investorId);
        if (parties.stream().allMatch(party -> party.getStatus() == ContractPartyStatus.SIGNED)) {
            return PadesLenderAttempt.alreadyCompleted();
        }
        if (parties.stream().anyMatch(party -> party.getStatus() != ContractPartyStatus.SIGNING)) {
            throw LoanBusinessException.conflict(
                    "INVESTOR_SIGNATURE_NOT_STARTED", "Chưa có yêu cầu SmartCA đang chờ xác nhận");
        }
        return resumedAttempt(contract, parties, investorId);
    }

    private PadesLenderAttempt resumedAttempt(LoanContract contract, List<ContractParty> parties, String investorId) {
        requireConsistentSigningState(parties);
        String transactionId = parties.getFirst().getSignatureTransactionId();
        ContractPdfSignature pending = pdfSignatureService.inFlight(contract.getId())
                .filter(signature -> signature.getSignerType() == ContractPartyType.LENDER
                        && signature.getSignerId().equals(investorId)
                        && signature.getProviderTransactionId().equals(transactionId))
                .orElseThrow(() -> LoanBusinessException.conflict(
                        "INVESTOR_SIGNATURE_STATE_MISMATCH", "Không tìm thấy phiên ký PDF đang chờ của nhà đầu tư"));
        return PadesLenderAttempt.of(pending, pdfSignatureService.command(contract, pending), true);
    }

    /**
     * Áp kết quả provider dưới khóa Contract. Hoàn tất thì nhúng CMS và ghi chữ ký bên cho vay; bị từ chối, quá hạn
     * hoặc hợp đồng đã đóng thì hủy lần ký và trả các phần vốn về chờ ký; chữ ký sai thì hủy và báo cho caller.
     */
    private PadesApplyResult applyPadesSubmission(
            String contractNumber,
            String investorId,
            Long signatureId,
            SignatureSubmission submission,
            boolean sessionExpired
    ) {
        LoanContract contract = lockedContract(contractNumber);
        List<ContractParty> parties = lenderPartiesForUpdate(contract, investorId);
        ContractPdfSignature signature = pdfSignatureService.find(signatureId);
        if (parties.stream().allMatch(party -> party.getStatus() == ContractPartyStatus.SIGNED)
                || !signature.isPrepared()) {
            return new PadesApplyResult(response(contract, parties.getFirst()), false);
        }
        Instant now = clock.instant();
        if (submission.status() == SignatureSubmissionStatus.REJECTED) {
            closeLenderSession(parties, signature, ContractPdfSignatureAbandonReason.PROVIDER_REJECTED, now);
            return new PadesApplyResult(response(contract, parties.getFirst()), false);
        }
        if (submission.status() != SignatureSubmissionStatus.COMPLETED) {
            if (sessionExpired) {
                closeLenderSession(parties, signature, ContractPdfSignatureAbandonReason.SIGNING_SESSION_EXPIRED, now);
            }
            return new PadesApplyResult(response(contract, parties.getFirst()), false);
        }
        if (contract.getStatus() != LoanContractStatus.PENDING_LENDER_SIGNATURES
                || !contract.getExpiresAt().isAfter(now)) {
            // Chữ ký đến muộn sau khi hợp đồng đã hết hạn/đóng không được nhúng vào hợp đồng đã đóng.
            closeLenderSession(parties, signature, ContractPdfSignatureAbandonReason.CONTRACT_CLOSED, now);
            return new PadesApplyResult(response(contract, parties.getFirst()), false);
        }
        ContractPdfSignatureService.PadesCompletion completion = pdfSignatureService.complete(
                contract, signature, submission.signatureValue(), now);
        if (!completion.signed()) {
            parties.forEach(party -> party.expireDigitalSignatureSession(now));
            partyRepository.saveAllAndFlush(parties);
            return new PadesApplyResult(response(contract, parties.getFirst()), true);
        }
        for (ContractParty party : parties) {
            party.completeDigitalSignature(
                    signature.getProviderTransactionId(), signature.getProviderDocumentId(),
                    completion.signature().getEvidenceHash(), investorId, now);
        }
        partyRepository.saveAllAndFlush(parties);
        advanceContractIfAllLendersSigned(contract, signableDocument(contract), now);
        return new PadesApplyResult(response(contract, parties.getFirst()), false);
    }

    private Boolean abandonOrphanedLenderSignature(String contractNumber, String investorId) {
        LoanContract contract = lockedContract(contractNumber);
        List<ContractParty> parties = lenderPartiesForUpdate(contract, investorId);
        if (parties.stream().anyMatch(party -> party.getStatus() == ContractPartyStatus.SIGNING)) {
            return false;
        }
        pdfSignatureService.inFlight(contract.getId())
                .filter(signature -> signature.getSignerType() == ContractPartyType.LENDER
                        && signature.getSignerId().equals(investorId))
                .ifPresent(signature -> pdfSignatureService.abandon(
                        signature, ContractPdfSignatureAbandonReason.SIGNING_SESSION_EXPIRED, clock.instant()));
        return true;
    }

    private void closeLenderSession(
            List<ContractParty> parties,
            ContractPdfSignature signature,
            ContractPdfSignatureAbandonReason reason,
            Instant now
    ) {
        pdfSignatureService.abandon(signature, reason, now);
        parties.forEach(party -> party.expireDigitalSignatureSession(now));
        partyRepository.saveAllAndFlush(parties);
    }

    /** Idempotency và tranh chấp của cùng nhà đầu tư; giữ nguyên quy tắc của luồng ký trước R5. */
    private LenderRequestState lenderRequestState(
            LoanContract contract,
            List<ContractParty> parties,
            String investorId,
            String normalizedKey,
            String requestHash
    ) {
        String contractNumber = contract.getContractNumber();
        if (parties.stream().allMatch(party -> party.getStatus() == ContractPartyStatus.SIGNED)) {
            boolean sameRequest = parties.stream().allMatch(party ->
                    stablePartyIdempotencyKey(contractNumber, investorId, normalizedKey, party.getCommitmentId())
                            .equals(party.getIdempotencyKey())
                            && requestHash.equals(party.getRequestHash()));
            if (sameRequest) {
                return LenderRequestState.ALREADY_SIGNED;
            }
            throw LoanBusinessException.conflict(
                    "CONTRACT_PARTY_ALREADY_SIGNED", "Nhà đầu tư đã ký với một yêu cầu khác");
        }
        if (parties.stream().anyMatch(party -> party.getStatus() == ContractPartyStatus.SIGNING)) {
            boolean sameRequest = parties.stream().allMatch(party ->
                    party.getStatus() == ContractPartyStatus.SIGNING
                            && stablePartyIdempotencyKey(
                                    contractNumber, investorId, normalizedKey, party.getCommitmentId())
                                    .equals(party.getIdempotencyKey())
                            && requestHash.equals(party.getRequestHash()));
            if (sameRequest) {
                return LenderRequestState.STARTED;
            }
            throw LoanBusinessException.conflict(
                    "CONTRACT_PARTY_SIGNATURE_IN_PROGRESS",
                    "Nhà đầu tư đang có một yêu cầu SmartCA khác chờ xác nhận");
        }
        return LenderRequestState.NEW;
    }

    /** Kiểm tra trước khi mở lần ký mới; PDF được nhận theo hash SIGNABLE hoặc hash bản đầu chuỗi PAdES (D47). */
    private void validateNewLenderSignature(LoanContract contract, SignLoanContractRequest request) {
        if (contract.getStatus() != LoanContractStatus.PENDING_LENDER_SIGNATURES) {
            throw LoanBusinessException.conflict(
                    "LENDER_SIGNATURE_WINDOW_CLOSED", "Hợp đồng không còn chờ chữ ký nhà đầu tư");
        }
        if (contract.getExpiresAt().isBefore(clock.instant())) {
            throw LoanBusinessException.conflict("CONTRACT_EXPIRED", "Hợp đồng đã hết hạn ký");
        }
        long currentVersion = contract.getVersion() == null ? 0L : contract.getVersion();
        if (currentVersion != request.version() || !contract.getDocumentHash().equals(request.documentHash())) {
            throw LoanBusinessException.conflict(
                    "CONTRACT_CONTENT_MISMATCH", "Phiên bản hoặc hash hợp đồng không khớp");
        }
        LoanContractDocument document = signableDocument(contract);
        boolean consentMatches = contract.isPadesSigned()
                ? pdfSignatureService.acceptsConsentHash(contract, document.getContentHash(), request.pdfDocumentHash())
                : request.pdfDocumentHash() != null && document.getContentHash().equals(request.pdfDocumentHash());
        if (!consentMatches
                || !document.getContentHash().equals(hashingService.sha256Bytes(document.contentCopy()))) {
            throw LoanBusinessException.conflict(
                    "CONTRACT_PDF_MISMATCH", "PDF đã xem không khớp artifact cần ký");
        }
        if (request.signatureMethod() != signatureProvider.method()) {
            throw LoanBusinessException.badRequest(
                    "SIGNATURE_METHOD_PROVIDER_MISMATCH",
                    "Phương thức ký không khớp provider đang được FINORA cấu hình");
        }
    }

    private InvestorSignatureContext prepareSignature(
            String contractNumber,
            String investorId,
            String normalizedKey,
            String requestHash,
            SignLoanContractRequest request
    ) {
        LoanContract contract = lockedContract(contractNumber);
        List<ContractParty> parties = lenderPartiesForUpdate(contract, investorId);
        LenderRequestState state = lenderRequestState(contract, parties, investorId, normalizedKey, requestHash);
        if (state == LenderRequestState.ALREADY_SIGNED) {
            return completedContext(contract, parties.getFirst(), investorId);
        }
        if (state == LenderRequestState.STARTED) {
            requireConsistentSigningState(parties);
            return startedContext(contract, parties.getFirst(), investorId, normalizedKey, requestHash);
        }
        validateNewLenderSignature(contract, request);
        LoanContractDocument document = signableDocument(contract);
        String transactionId = stableTransactionId(contractNumber, investorId, normalizedKey);
        String documentId = "FINORA-" + document.getContentHash().substring(0, 24);
        if (signatureProvider.type() == SignatureProviderType.VNPT_SMART_CA) {
            Instant now = clock.instant();
            for (ContractParty party : parties) {
                party.beginDigitalSignature(
                        transactionId, documentId,
                        stablePartyIdempotencyKey(
                                contractNumber, investorId, normalizedKey, party.getCommitmentId()),
                        requestHash, investorId, now);
            }
            partyRepository.saveAllAndFlush(parties);
        }
        return new InvestorSignatureContext(
                contractNumber, investorId, contract.getDocumentHash(), document.getContentHash(),
                normalizedKey, requestHash, transactionId, documentId, request.signatureMethod(),
                false, false);
    }

    private InvestorSignatureContext existingSignature(String contractNumber, String investorId) {
        LoanContract contract = lockedContract(contractNumber);
        List<ContractParty> parties = lenderPartiesForUpdate(contract, investorId);
        if (parties.stream().allMatch(party -> party.getStatus() == ContractPartyStatus.SIGNED)) {
            return completedContext(contract, parties.getFirst(), investorId);
        }
        if (parties.stream().anyMatch(party -> party.getStatus() != ContractPartyStatus.SIGNING)) {
            throw LoanBusinessException.conflict(
                    "INVESTOR_SIGNATURE_NOT_STARTED", "Chưa có yêu cầu SmartCA đang chờ xác nhận");
        }
        requireConsistentSigningState(parties);
        return startedContext(
                contract, parties.getFirst(), investorId,
                parties.getFirst().getIdempotencyKey(), parties.getFirst().getRequestHash());
    }

    private InvestorContractResponse applySubmission(
            InvestorSignatureContext context,
            SignatureSubmission submission
    ) {
        LoanContract contract = lockedContract(context.contractNumber());
        List<ContractParty> parties = lenderPartiesForUpdate(contract, context.investorId());
        if (parties.stream().allMatch(party -> party.getStatus() == ContractPartyStatus.SIGNED)) {
            return response(contract, parties.getFirst());
        }
        Instant now = clock.instant();
        if (submission.status() == SignatureSubmissionStatus.REJECTED) {
            parties.forEach(party -> party.resetRejectedDigitalSignature(context.investorId(), now));
            partyRepository.saveAllAndFlush(parties);
            return response(contract, parties.getFirst());
        }
        if (submission.status() != SignatureSubmissionStatus.COMPLETED) {
            return response(contract, parties.getFirst());
        }
        String evidenceHash = evidenceHash(submission);
        for (ContractParty party : parties) {
            if (signatureProvider.type() == SignatureProviderType.VNPT_SMART_CA) {
                party.completeDigitalSignature(
                        submission.providerTransactionId(), submission.documentId(),
                        evidenceHash, context.investorId(), now);
            } else {
                party.signMock(
                        submission.providerTransactionId(), evidenceHash,
                        stablePartyIdempotencyKey(
                                context.contractNumber(), context.investorId(),
                                context.idempotencyKey(), party.getCommitmentId()),
                        context.requestHash(), context.investorId(), now);
            }
        }
        partyRepository.saveAllAndFlush(parties);
        advanceContractIfAllLendersSigned(contract, signableDocument(contract), now);
        return response(contract, parties.getFirst());
    }

    private void advanceContractIfAllLendersSigned(
            LoanContract contract,
            LoanContractDocument document,
            Instant now
    ) {
        if (partyRepository.countByContractIdAndPartyTypeAndStatusNot(
                contract.getId(), ContractPartyType.LENDER, ContractPartyStatus.SIGNED) == 0) {
            contract.markBorrowerSignatureReady("SYSTEM-LENDER-SIGNATURES", now);
            contractRepository.saveAndFlush(contract);
            historyRepository.saveAndFlush(LoanContractStatusHistory.create(
                    contract.getId(), LoanContractStatus.PENDING_LENDER_SIGNATURES,
                    LoanContractStatus.PENDING_BORROWER_SIGNATURE, "ALL_LENDERS_SIGNED",
                    ActorType.SYSTEM, "SYSTEM-LENDER-SIGNATURES", now,
                    TraceContext.currentTraceIdOrCreate()));
            LoanApplication application = applicationRepository.findById(contract.getApplicationId()).orElseThrow();
            // P1 (W3F): người nhận nằm ngay trong event; borrowerId là ID logic đã lưu trên hồ sơ, không phải PII.
            outboxService.recordForPublication(
                    "LoanContract", contract.getContractNumber(), "BorrowerSignatureRequested", 1,
                    new BorrowerSignatureRequestedEventData(
                            contract.getApplicationId(), application.getApplicationNumber(),
                            contract.getContractNumber(), contract.getDocumentHash(), document.getContentHash(),
                            (int) partyRepository.findByContractIdOrderByPartyTypeAscCommitmentIdAsc(
                                    contract.getId()).stream()
                                    .filter(party -> party.getPartyType() == ContractPartyType.LENDER)
                                    .map(ContractParty::getPartyId).distinct().count(),
                            now, contract.getExpiresAt(), application.getBorrowerId()));
        }
    }

    private SignatureSubmission submit(SignatureCommand command) {
        try {
            return signatureProvider.submit(command);
        } catch (SmartCaIntegrationException exception) {
            throw LoanBusinessException.serviceUnavailable(exception.getCode(), exception.getMessage());
        }
    }

    private SignatureSubmission submitDigest(DigestSignatureCommand command) {
        try {
            return signatureProvider.submitDigest(command);
        } catch (SmartCaIntegrationException exception) {
            throw LoanBusinessException.serviceUnavailable(exception.getCode(), exception.getMessage());
        }
    }

    private SignatureSubmission status(String transactionId, String documentId) {
        try {
            return signatureProvider.status(transactionId, documentId);
        } catch (SmartCaIntegrationException exception) {
            throw LoanBusinessException.serviceUnavailable(exception.getCode(), exception.getMessage());
        }
    }

    private SigningCertificate signingCertificate(String investorId) {
        try {
            return signatureProvider.signingCertificate(investorId);
        } catch (SmartCaIntegrationException exception) {
            throw LoanBusinessException.serviceUnavailable(exception.getCode(), exception.getMessage());
        }
    }

    private ContractPdfSignatureService.PadesSignerRequest signerRequest(
            String investorId,
            SigningCertificate certificate,
            String normalizedKey,
            String requestHash
    ) {
        return new ContractPdfSignatureService.PadesSignerRequest(
                ContractPartyType.LENDER, investorId, certificate, signatureProvider.type(),
                signatureProvider.method(), normalizedKey, requestHash);
    }

    private String evidenceHash(SignatureSubmission submission) {
        return hashingService.sha256(new InvestorSignatureProof(
                signatureProvider.type(), submission.providerTransactionId(), submission.documentId(),
                submission.signatureValue(), submission.timestampSignature()));
    }

    private void requireConsistentSigningState(List<ContractParty> parties) {
        ContractParty first = parties.getFirst();
        boolean consistent = parties.stream().allMatch(party ->
                java.util.Objects.equals(first.getSignatureTransactionId(), party.getSignatureTransactionId())
                        && java.util.Objects.equals(
                                first.getSignatureDocumentId(), party.getSignatureDocumentId()));
        if (!consistent) {
            throw LoanBusinessException.conflict(
                    "INVESTOR_SIGNATURE_STATE_MISMATCH",
                    "Các phần vốn của nhà đầu tư không cùng một giao dịch SmartCA");
        }
    }

    private InvestorSignatureContext completedContext(
            LoanContract contract,
            ContractParty party,
            String investorId
    ) {
        LoanContractDocument document = signableDocument(contract);
        return new InvestorSignatureContext(
                contract.getContractNumber(), investorId, contract.getDocumentHash(),
                document.getContentHash(), party.getIdempotencyKey(), party.getRequestHash(),
                party.getSignatureTransactionId(), party.getSignatureDocumentId(),
                party.getSignatureMethod(), true, true);
    }

    private InvestorSignatureContext startedContext(
            LoanContract contract,
            ContractParty party,
            String investorId,
            String idempotencyKey,
            String requestHash
    ) {
        LoanContractDocument document = signableDocument(contract);
        return new InvestorSignatureContext(
                contract.getContractNumber(), investorId, contract.getDocumentHash(),
                document.getContentHash(), idempotencyKey, requestHash,
                party.getSignatureTransactionId(), party.getSignatureDocumentId(),
                party.getSignatureMethod(), false, true);
    }

    private <T> T transactionally(Supplier<T> work) {
        T result = new TransactionTemplate(transactionManager).execute(status -> work.get());
        if (result == null) {
            throw new IllegalStateException("Transaction ký hợp đồng nhà đầu tư không trả kết quả");
        }
        return result;
    }

    private LoanContract contract(String contractNumber) {
        return contractRepository.findByContractNumber(contractNumber)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Loan Contract", "contractNumber", contractNumber));
    }

    private LoanContract lockedContract(String contractNumber) {
        return contractRepository.findByContractNumberForUpdate(contractNumber)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Loan Contract", "contractNumber", contractNumber));
    }

    private List<ContractParty> lenderParties(LoanContract contract, String investorId) {
        List<ContractParty> parties = partyRepository.findByContractIdOrderByPartyTypeAscCommitmentIdAsc(
                        contract.getId()).stream()
                .filter(party -> party.getPartyType() == ContractPartyType.LENDER
                        && party.getPartyId().equals(investorId))
                .toList();
        if (parties.isEmpty()) {
            throw LoanBusinessException.forbidden(
                    "CONTRACT_PARTY_ACCESS_DENIED", "Bạn không phải bên cho vay của hợp đồng này");
        }
        return parties;
    }

    private List<ContractParty> lenderPartiesForUpdate(LoanContract contract, String investorId) {
        List<ContractParty> parties = partyRepository.findForUpdate(
                contract.getId(), ContractPartyType.LENDER, investorId);
        if (parties.isEmpty()) {
            throw LoanBusinessException.forbidden(
                    "CONTRACT_PARTY_ACCESS_DENIED", "Bạn không phải bên cho vay của hợp đồng này");
        }
        return parties;
    }

    private LoanContractDocument signableDocument(LoanContract contract) {
        return documentRepository.findByContractIdAndArtifactType(
                        contract.getId(), ContractPdfArtifactType.SIGNABLE)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Loan Contract PDF", "contractNumber", contract.getContractNumber()));
    }

    /**
     * PDF tại {@code /document}: SIGNABLE trong lúc các bên còn ký (mọi bên đọc và đồng ý cùng một nội dung); sau khi
     * đủ chữ ký là bản PAdES ký đủ, hoặc receipt tách rời với hợp đồng trước R5.
     */
    private LoanContractDocument currentDocument(LoanContract contract) {
        if (contract.getStatus() == LoanContractStatus.SIGNED
                || contract.getStatus() == LoanContractStatus.EFFECTIVE
                || contract.getStatus() == LoanContractStatus.COMPLETED) {
            for (ContractPdfArtifactType type
                    : List.of(ContractPdfArtifactType.SIGNED_PDF, ContractPdfArtifactType.SIGNED_RECEIPT)) {
                LoanContractDocument document = documentRepository.findByContractIdAndArtifactType(
                        contract.getId(), type).orElse(null);
                if (document != null) {
                    return document;
                }
            }
        }
        return signableDocument(contract);
    }

    private InvestorContractResponse response(LoanContract contract, ContractParty party) {
        LoanContractDocument document = currentDocument(contract);
        List<ContractParty> all = partyRepository.findByContractIdOrderByPartyTypeAscCommitmentIdAsc(
                contract.getId());
        BigDecimal investorAmount = all.stream()
                .filter(item -> item.getPartyType() == ContractPartyType.LENDER
                        && item.getPartyId().equals(party.getPartyId()))
                .map(ContractParty::getAllocationAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        List<ContractPdfSignatureSummary> signatures = contract.isPadesSigned()
                ? pdfSignatureService.signedSummaries(contract.getId())
                : List.of();
        return new InvestorContractResponse(
                contract.getContractNumber(), contract.getStatus(), party.getStatus(),
                contract.getVersion() == null ? 0L : contract.getVersion(), contract.getDocumentHash(),
                document.getContentHash(), investorAmount, contract.getTermMonths(),
                (int) all.stream().filter(item -> item.getPartyType() == ContractPartyType.LENDER).count(),
                (int) all.stream().filter(item -> item.getPartyType() == ContractPartyType.LENDER
                        && item.getStatus() != ContractPartyStatus.SIGNED).count(),
                signatureProvider.type(), signatureProvider.method(), contract.getExpiresAt(),
                contract.getSignatureProfile(), signatures.size(),
                signatures.isEmpty() ? null : signatures.getLast().contentHash(),
                documentRepository.findContentHash(contract.getId(), ContractPdfArtifactType.SIGNED_RECEIPT)
                        .orElse(null));
    }

    private String stableTransactionId(String contractNumber, String investorId, String key) {
        return "FINORA-INV-" + UUID.nameUUIDFromBytes(
                (contractNumber + "|" + investorId + "|" + key).getBytes(StandardCharsets.UTF_8));
    }

    private String stablePartyIdempotencyKey(
            String contractNumber,
            String investorId,
            String key,
            Long commitmentId
    ) {
        return "INV-" + UUID.nameUUIDFromBytes(
                (contractNumber + "|" + investorId + "|" + key + "|" + commitmentId)
                        .getBytes(StandardCharsets.UTF_8));
    }

    private String requireIdempotencyKey(String key) {
        if (key == null || key.isBlank()) {
            throw new IllegalArgumentException("Idempotency-Key không được để trống");
        }
        return key.trim();
    }

    private enum LenderRequestState {
        NEW,
        STARTED,
        ALREADY_SIGNED
    }

    private record PadesLenderAttempt(
            boolean completed,
            boolean resumed,
            Long signatureId,
            String transactionId,
            String documentId,
            DigestSignatureCommand command
    ) {
        private static PadesLenderAttempt alreadyCompleted() {
            return new PadesLenderAttempt(true, false, null, null, null, null);
        }

        private static PadesLenderAttempt of(
                ContractPdfSignature signature,
                DigestSignatureCommand command,
                boolean resumed
        ) {
            return new PadesLenderAttempt(
                    false, resumed, signature.getId(), signature.getProviderTransactionId(),
                    signature.getProviderDocumentId(), command);
        }
    }

    private record PadesApplyResult(InvestorContractResponse response, boolean invalidSignature) {
    }

    private record InvestorConsentFingerprint(
            String contractNumber,
            String investorId,
            SignLoanContractRequest request
    ) {
    }

    private record InvestorSignatureProof(
            SignatureProviderType provider,
            String transactionId,
            String documentId,
            String signatureValue,
            String timestampSignature
    ) {
    }

    private record InvestorSignatureContext(
            String contractNumber,
            String investorId,
            String documentHash,
            String pdfDocumentHash,
            String idempotencyKey,
            String requestHash,
            String transactionId,
            String documentId,
            com.finora.loan.domain.contract.SignatureMethod signatureMethod,
            boolean alreadySigned,
            boolean alreadyStarted
    ) {
        private SignatureCommand command() {
            return new SignatureCommand(
                    contractNumber, investorId, documentHash, pdfDocumentHash,
                    idempotencyKey, transactionId, documentId, signatureMethod);
        }
    }
}
