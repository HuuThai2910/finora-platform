package com.finora.loan.service.contract.impl;

import com.finora.common.exception.ResourceNotFoundException;
import com.finora.common.logging.TraceContext;
import com.finora.common.security.SecurityUtils;
import com.finora.loan.domain.application.ActorType;
import com.finora.loan.domain.contract.ContractParty;
import com.finora.loan.domain.contract.ContractPartyStatus;
import com.finora.loan.domain.contract.ContractPartyType;
import com.finora.loan.domain.contract.ContractPdfArtifactType;
import com.finora.loan.domain.contract.LoanContract;
import com.finora.loan.domain.contract.LoanContractDocument;
import com.finora.loan.domain.contract.LoanContractStatus;
import com.finora.loan.domain.contract.LoanContractStatusHistory;
import com.finora.loan.domain.contract.SignatureProviderType;
import com.finora.loan.dto.contract.request.SignLoanContractRequest;
import com.finora.loan.dto.contract.response.InvestorContractResponse;
import com.finora.loan.exception.LoanBusinessException;
import com.finora.loan.integration.signature.SignatureCommand;
import com.finora.loan.integration.signature.SignatureProvider;
import com.finora.loan.integration.signature.SignatureSubmission;
import com.finora.loan.integration.signature.SignatureSubmissionStatus;
import com.finora.loan.integration.signature.SmartCaIntegrationException;
import com.finora.loan.messaging.event.BorrowerSignatureRequestedEventData;
import com.finora.loan.repository.application.LoanApplicationRepository;
import com.finora.loan.repository.contract.ContractPartyRepository;
import com.finora.loan.repository.contract.LoanContractDocumentRepository;
import com.finora.loan.repository.contract.LoanContractRepository;
import com.finora.loan.repository.contract.LoanContractStatusHistoryRepository;
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
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

@Service
@RequiredArgsConstructor
public class InvestorContractServiceImpl implements InvestorContractService {

    private final LoanContractRepository contractRepository;
    private final ContractPartyRepository partyRepository;
    private final LoanContractDocumentRepository documentRepository;
    private final LoanContractStatusHistoryRepository historyRepository;
    private final LoanApplicationRepository applicationRepository;
    private final SignatureProvider signatureProvider;
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
    public InvestorContractResponse sign(
            String contractNumber,
            String idempotencyKey,
            SignLoanContractRequest request
    ) {
        String investorId = SecurityUtils.getCurrentUserId();
        String normalizedKey = requireIdempotencyKey(idempotencyKey);
        String requestHash = hashingService.sha256(new InvestorConsentFingerprint(
                contractNumber, investorId, request));
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

    private InvestorSignatureContext prepareSignature(
            String contractNumber,
            String investorId,
            String normalizedKey,
            String requestHash,
            SignLoanContractRequest request
    ) {
        LoanContract contract = contractRepository.findByContractNumberForUpdate(contractNumber)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Loan Contract", "contractNumber", contractNumber));
        List<ContractParty> parties = lenderPartiesForUpdate(contract, investorId);
        boolean allCallerPartiesSigned = parties.stream()
                .allMatch(party -> party.getStatus() == ContractPartyStatus.SIGNED);
        if (allCallerPartiesSigned) {
            boolean sameRequest = parties.stream().allMatch(party ->
                    stablePartyIdempotencyKey(contractNumber, investorId, normalizedKey, party.getCommitmentId())
                            .equals(party.getIdempotencyKey())
                            && requestHash.equals(party.getRequestHash()));
            if (sameRequest) {
                return completedContext(contract, parties.getFirst(), investorId);
            }
            throw LoanBusinessException.conflict(
                    "CONTRACT_PARTY_ALREADY_SIGNED", "Nhà đầu tư đã ký với một yêu cầu khác");
        }
        boolean anyCallerPartySigning = parties.stream()
                .anyMatch(party -> party.getStatus() == ContractPartyStatus.SIGNING);
        if (anyCallerPartySigning) {
            boolean sameRequest = parties.stream().allMatch(party ->
                    party.getStatus() == ContractPartyStatus.SIGNING
                            && stablePartyIdempotencyKey(
                                    contractNumber, investorId, normalizedKey, party.getCommitmentId())
                                    .equals(party.getIdempotencyKey())
                            && requestHash.equals(party.getRequestHash()));
            if (sameRequest) {
                requireConsistentSigningState(parties);
                return startedContext(contract, parties.getFirst(), investorId, normalizedKey, requestHash);
            }
            throw LoanBusinessException.conflict(
                    "CONTRACT_PARTY_SIGNATURE_IN_PROGRESS",
                    "Nhà đầu tư đang có một yêu cầu SmartCA khác chờ xác nhận");
        }
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
        if (request.pdfDocumentHash() == null
                || !document.getContentHash().equals(request.pdfDocumentHash())
                || !document.getContentHash().equals(hashingService.sha256Bytes(document.contentCopy()))) {
            throw LoanBusinessException.conflict(
                    "CONTRACT_PDF_MISMATCH", "PDF đã xem không khớp artifact cần ký");
        }
        if (request.signatureMethod() != signatureProvider.method()) {
            throw LoanBusinessException.badRequest(
                    "SIGNATURE_METHOD_PROVIDER_MISMATCH",
                    "Phương thức ký không khớp provider đang được FINORA cấu hình");
        }
        String transactionId = stableTransactionId(contractNumber, investorId, normalizedKey);
        String documentId = "FINORA-" + document.getContentHash().substring(0, 24);
        SignatureCommand command = new SignatureCommand(
                contractNumber, investorId, contract.getDocumentHash(), document.getContentHash(),
                normalizedKey, transactionId, documentId, request.signatureMethod());
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
        LoanContract contract = contractRepository.findByContractNumberForUpdate(contractNumber)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Loan Contract", "contractNumber", contractNumber));
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
        LoanContract contract = contractRepository.findByContractNumberForUpdate(context.contractNumber())
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Loan Contract", "contractNumber", context.contractNumber()));
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
            String applicationNumber = applicationRepository.findById(contract.getApplicationId())
                    .orElseThrow().getApplicationNumber();
            outboxService.recordForPublication(
                    "LoanContract", contract.getContractNumber(), "BorrowerSignatureRequested", 1,
                    new BorrowerSignatureRequestedEventData(
                            contract.getApplicationId(), applicationNumber, contract.getContractNumber(),
                            contract.getDocumentHash(), document.getContentHash(),
                            (int) partyRepository.findByContractIdOrderByPartyTypeAscCommitmentIdAsc(
                                    contract.getId()).stream()
                                    .filter(party -> party.getPartyType() == ContractPartyType.LENDER)
                                    .map(ContractParty::getPartyId).distinct().count(),
                            now, contract.getExpiresAt()));
        }
    }

    private SignatureSubmission submit(SignatureCommand command) {
        try {
            return signatureProvider.submit(command);
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

    private LoanContractDocument currentDocument(LoanContract contract) {
        if (contract.getStatus() == LoanContractStatus.SIGNED
                || contract.getStatus() == LoanContractStatus.EFFECTIVE
                || contract.getStatus() == LoanContractStatus.COMPLETED) {
            LoanContractDocument receipt = documentRepository.findByContractIdAndArtifactType(
                            contract.getId(), ContractPdfArtifactType.SIGNED_RECEIPT)
                    .orElse(null);
            if (receipt != null) {
                return receipt;
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
        return new InvestorContractResponse(
                contract.getContractNumber(), contract.getStatus(), party.getStatus(),
                contract.getVersion() == null ? 0L : contract.getVersion(), contract.getDocumentHash(),
                document.getContentHash(), investorAmount, contract.getTermMonths(),
                (int) all.stream().filter(item -> item.getPartyType() == ContractPartyType.LENDER).count(),
                (int) all.stream().filter(item -> item.getPartyType() == ContractPartyType.LENDER
                        && item.getStatus() != ContractPartyStatus.SIGNED).count(),
                signatureProvider.type(), signatureProvider.method(), contract.getExpiresAt());
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
