package com.finora.loan.service.contract.impl;

import com.finora.common.exception.ResourceNotFoundException;
import com.finora.common.security.SecurityUtils;
import com.finora.loan.domain.application.LoanApplication;
import com.finora.loan.domain.contract.ConsentAction;
import com.finora.loan.domain.contract.ContractPdfArtifactType;
import com.finora.loan.domain.contract.LoanContract;
import com.finora.loan.domain.contract.LoanContractDocument;
import com.finora.loan.domain.contract.LoanContractStatus;
import com.finora.loan.domain.core.ScheduleCalculationSnapshot;
import com.finora.loan.dto.common.PageResponse;
import com.finora.loan.dto.contract.request.DeclineLoanContractRequest;
import com.finora.loan.dto.contract.request.SignLoanContractRequest;
import com.finora.loan.dto.contract.response.LoanContractActionResponse;
import com.finora.loan.dto.contract.response.LoanContractDetailResponse;
import com.finora.loan.dto.contract.response.LoanContractHistoryResponse;
import com.finora.loan.dto.contract.response.LoanContractSummaryResponse;
import com.finora.loan.exception.LoanBusinessException;
import com.finora.loan.integration.signature.SignatureCommand;
import com.finora.loan.integration.signature.SignatureEvidence;
import com.finora.loan.integration.signature.SignatureProvider;
import com.finora.loan.integration.signature.SignatureSubmission;
import com.finora.loan.integration.signature.SignatureSubmissionStatus;
import com.finora.loan.integration.signature.SmartCaIntegrationException;
import com.finora.loan.mapper.contract.LoanContractMapper;
import com.finora.loan.repository.application.LoanApplicationRepository;
import com.finora.loan.repository.contract.LoanContractRepository;
import com.finora.loan.repository.contract.LoanContractDocumentRepository;
import com.finora.loan.repository.contract.LoanContractStatusHistoryRepository;
import com.finora.loan.repository.core.ScheduleCalculationSnapshotRepository;
import com.finora.loan.service.contract.ContractConsentResult;
import com.finora.loan.service.contract.LoanContractService;
import com.finora.loan.service.contract.LoanContractPdfContent;
import com.finora.loan.service.contract.LoanContractStateService;
import com.finora.loan.service.contract.SignaturePreparation;
import com.finora.loan.support.HashingService;
import java.util.Map;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Slf4j
public class LoanContractServiceImpl implements LoanContractService {

    private final LoanContractRepository contractRepository;
    private final LoanContractDocumentRepository contractDocumentRepository;
    private final LoanContractStatusHistoryRepository historyRepository;
    private final LoanApplicationRepository applicationRepository;
    private final ScheduleCalculationSnapshotRepository scheduleRepository;
    private final LoanContractStateService stateService;
    private final LoanContractMapper mapper;
    private final HashingService hashingService;
    private final SignatureProvider signatureProvider;

    /** Một page Contract + một batch Application; document lớn không được tải vào response summary. */
    @Override
    @Transactional(readOnly = true)
    public PageResponse<LoanContractSummaryResponse> listMine(int page, int size) {
        Page<LoanContract> contracts = contractRepository.findByBorrowerId(
                SecurityUtils.getCurrentUserId(),
                PageRequest.of(page, size, Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("id")))
        );
        Map<Long, LoanApplication> applications = applicationRepository
                .findAllById(contracts.getContent().stream().map(LoanContract::getApplicationId).toList())
                .stream()
                .collect(Collectors.toMap(LoanApplication::getId, Function.identity()));
        return PageResponse.from(contracts.map(contract -> mapper.toSummary(
                contract, applications.get(contract.getApplicationId()).getApplicationNumber())));
    }

    @Override
    @Transactional(readOnly = true)
    public LoanContractDetailResponse detail(String contractNumber) {
        LoanContract contract = contract(contractNumber);
        contract.requireOwner(SecurityUtils.getCurrentUserId());
        LoanApplication application = applicationRepository.findById(contract.getApplicationId())
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Loan Application", "id", contract.getApplicationId()));
        ScheduleCalculationSnapshot schedule = scheduleRepository.findById(contract.getCalculationSnapshotId())
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Schedule Calculation Snapshot", "id", contract.getCalculationSnapshotId()));
        return mapper.toDetail(
                contract, application, schedule, currentPdf(contract),
                signatureProvider.type(), signatureProvider.method());
    }

    @Override
    @Transactional(readOnly = true)
    public LoanContractPdfContent document(String contractNumber) {
        LoanContract contract = contract(contractNumber);
        contract.requireOwner(SecurityUtils.getCurrentUserId());
        LoanContractDocument document = currentPdf(contract);
        if (document == null) {
            throw new ResourceNotFoundException(
                    "Loan Contract PDF", "contractNumber", contractNumber);
        }
        return new LoanContractPdfContent(
                contractNumber + ".pdf", document.getContentType(), document.getContentHash(),
                document.contentCopy()
        );
    }

    @Override
    public LoanContractActionResponse sign(
            String contractNumber,
            String idempotencyKey,
            SignLoanContractRequest request
    ) {
        String normalizedKey = idempotencyKey.trim();
        String requestHash = hashingService.sha256(new ConsentFingerprint("SIGN", request));
        String borrowerId = SecurityUtils.getCurrentUserId();
        ContractConsentResult duplicate = stateService.findCommittedByKey(
                contractNumber, normalizedKey, requestHash, ConsentAction.SIGN, borrowerId);
        if (duplicate != null) {
            if (duplicate.contract().getStatus() == LoanContractStatus.SIGNING) {
                return refreshSignature(contractNumber);
            }
            return mapper.toAction(duplicate.contract());
        }
        SignaturePreparation preparation = stateService.prepareSignature(contractNumber, request, borrowerId);
        if (request.signatureMethod() != signatureProvider.method()) {
            throw LoanBusinessException.badRequest(
                    "SIGNATURE_METHOD_PROVIDER_MISMATCH",
                    "Phương thức ký không khớp provider đang được FINORA cấu hình");
        }
        String transactionId = providerTransactionId(contractNumber, normalizedKey);
        String documentId = "FINORA-" + preparation.pdfDocumentHash().substring(0, 24);
        SignatureCommand command = new SignatureCommand(
                preparation.contractNumber(), preparation.borrowerId(), preparation.documentHash(),
                preparation.pdfDocumentHash(), normalizedKey, transactionId, documentId,
                request.signatureMethod());
        if (signatureProvider.type() == com.finora.loan.domain.contract.SignatureProviderType.VNPT_SMART_CA) {
            ContractConsentResult started = stateService.beginDigitalSignature(
                    contractNumber, normalizedKey, requestHash, request,
                    transactionId, documentId, borrowerId);
            rejectExpired(started);
            SignatureSubmission submission = submit(command);
            LoanContract contract = applySubmission(started.contract(), submission, borrowerId);
            return mapper.toAction(contract);
        }
        SignatureSubmission submission = submit(command);
        SignatureEvidence evidence = evidence(submission);
        ContractConsentResult result = executeWithDuplicateRecovery(
                contractNumber, normalizedKey, requestHash, ConsentAction.SIGN,
                () -> stateService.sign(
                        contractNumber, normalizedKey, requestHash, request, evidence, borrowerId));
        rejectExpired(result);
        log.info("Borrower đã ký Contract: contractNumber={}, actorId={}",
                contractNumber, borrowerId);
        return mapper.toAction(result.contract());
    }

    @Override
    public LoanContractActionResponse refreshSignature(String contractNumber) {
        String borrowerId = SecurityUtils.getCurrentUserId();
        LoanContract contract = contract(contractNumber);
        contract.requireOwner(borrowerId);
        if (contract.getStatus() != LoanContractStatus.SIGNING) {
            return mapper.toAction(contract);
        }
        SignatureSubmission submission = status(
                contract.getSignatureTransactionId(), contract.getSignatureDocumentId());
        if (submission.status() == SignatureSubmissionStatus.NOT_FOUND) {
            LoanContractDocument document = currentPdf(contract);
            if (document == null) {
                throw LoanBusinessException.conflict(
                        "CONTRACT_PDF_MISSING", "Không tìm thấy PDF dùng cho yêu cầu SmartCA");
            }
            submission = submit(new SignatureCommand(
                    contract.getContractNumber(), contract.getBorrowerId(), contract.getDocumentHash(),
                    document.getContentHash(), contract.getConsentIdempotencyKey(),
                    contract.getSignatureTransactionId(), contract.getSignatureDocumentId(),
                    contract.getSignatureMethod()));
        }
        return mapper.toAction(applySubmission(contract, submission, borrowerId));
    }

    private LoanContract applySubmission(
            LoanContract contract,
            SignatureSubmission submission,
            String borrowerId
    ) {
        if (submission.status() == SignatureSubmissionStatus.COMPLETED) {
            return stateService.completeDigitalSignature(
                    contract.getContractNumber(), evidence(submission), submission.documentId(), borrowerId);
        }
        if (submission.status() == SignatureSubmissionStatus.REJECTED) {
            return stateService.resetRejectedDigitalSignature(contract.getContractNumber(), borrowerId);
        }
        return contract;
    }

    private SignatureEvidence evidence(SignatureSubmission submission) {
        String evidenceHash = hashingService.sha256(new SignatureProof(
                signatureProvider.type(), submission.providerTransactionId(), submission.documentId(),
                submission.signatureValue(), submission.timestampSignature()));
        return new SignatureEvidence(
                signatureProvider.type(), signatureProvider.method(),
                submission.providerTransactionId(), evidenceHash);
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

    private String providerTransactionId(String contractNumber, String idempotencyKey) {
        UUID stableId = UUID.nameUUIDFromBytes(
                (contractNumber + "|" + idempotencyKey).getBytes(StandardCharsets.UTF_8));
        return "FINORA-" + stableId;
    }

    @Override
    public LoanContractActionResponse decline(
            String contractNumber,
            String idempotencyKey,
            DeclineLoanContractRequest request
    ) {
        String normalizedKey = idempotencyKey.trim();
        String requestHash = hashingService.sha256(new ConsentFingerprint("DECLINE", request));
        String userId = SecurityUtils.getCurrentUserId();
        ContractConsentResult result = executeWithDuplicateRecovery(
                contractNumber, normalizedKey, requestHash, ConsentAction.DECLINE,
                () -> stateService.decline(
                        contractNumber, normalizedKey, requestHash, request, userId));
        rejectExpired(result);
        log.info("Borrower đã từ chối Contract: contractNumber={}, reasonCode={}, actorId={}",
                contractNumber, request.reasonCode(), userId);
        return mapper.toAction(result.contract());
    }

    @Override
    @Transactional(readOnly = true)
    public PageResponse<LoanContractHistoryResponse> history(String contractNumber, int page, int size) {
        LoanContract contract = contract(contractNumber);
        contract.requireOwner(SecurityUtils.getCurrentUserId());
        return PageResponse.from(historyRepository.findByContractId(
                        contract.getId(),
                        PageRequest.of(page, size,
                                Sort.by(Sort.Order.desc("occurredAt"), Sort.Order.desc("id"))))
                .map(mapper::toHistory));
    }

    private ContractConsentResult executeWithDuplicateRecovery(
            String contractNumber,
            String idempotencyKey,
            String requestHash,
            ConsentAction action,
            java.util.function.Supplier<ContractConsentResult> command
    ) {
        try {
            return command.get();
        } catch (DataIntegrityViolationException conflict) {
            ContractConsentResult committed = stateService.findCommittedByKey(
                    contractNumber, idempotencyKey, requestHash, action, SecurityUtils.getCurrentUserId());
            if (committed != null && committed.contract().getContractNumber().equals(contractNumber)) {
                return committed;
            }
            throw conflict;
        }
    }

    private void rejectExpired(ContractConsentResult result) {
        if (result.expiredDuringRequest()) {
            throw LoanBusinessException.conflict("CONTRACT_EXPIRED", "Hợp đồng đã hết hạn ký");
        }
    }

    private LoanContract contract(String contractNumber) {
        return contractRepository.findByContractNumber(contractNumber)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Loan Contract", "contractNumber", contractNumber));
    }

    private LoanContractDocument currentPdf(LoanContract contract) {
        if (contract.getStatus() == LoanContractStatus.SIGNED
                || contract.getStatus() == LoanContractStatus.EFFECTIVE
                || contract.getStatus() == LoanContractStatus.COMPLETED) {
            LoanContractDocument receipt = contractDocumentRepository
                    .findByContractIdAndArtifactType(contract.getId(), ContractPdfArtifactType.SIGNED_RECEIPT)
                    .orElse(null);
            if (receipt != null) {
                return receipt;
            }
        }
        return contractDocumentRepository
                .findByContractIdAndArtifactType(contract.getId(), ContractPdfArtifactType.SIGNABLE)
                .orElse(null);
    }

    private record ConsentFingerprint(String action, Object request) {
    }

    private record SignatureProof(
            com.finora.loan.domain.contract.SignatureProviderType provider,
            String transactionId,
            String documentId,
            String signatureValue,
            String timestampSignature
    ) {
    }
}
