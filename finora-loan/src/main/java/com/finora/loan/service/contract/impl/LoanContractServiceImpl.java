package com.finora.loan.service.contract.impl;

import com.finora.common.exception.ResourceNotFoundException;
import com.finora.loan.config.MockCurrentUserProvider;
import com.finora.loan.domain.application.LoanApplication;
import com.finora.loan.domain.contract.ConsentAction;
import com.finora.loan.domain.contract.LoanContract;
import com.finora.loan.domain.core.ScheduleCalculationSnapshot;
import com.finora.loan.dto.common.PageResponse;
import com.finora.loan.dto.contract.request.DeclineLoanContractRequest;
import com.finora.loan.dto.contract.request.SignLoanContractRequest;
import com.finora.loan.dto.contract.response.LoanContractActionResponse;
import com.finora.loan.dto.contract.response.LoanContractDetailResponse;
import com.finora.loan.dto.contract.response.LoanContractHistoryResponse;
import com.finora.loan.dto.contract.response.LoanContractSummaryResponse;
import com.finora.loan.exception.LoanBusinessException;
import com.finora.loan.mapper.contract.LoanContractMapper;
import com.finora.loan.repository.application.LoanApplicationRepository;
import com.finora.loan.repository.contract.LoanContractRepository;
import com.finora.loan.repository.contract.LoanContractStatusHistoryRepository;
import com.finora.loan.repository.core.ScheduleCalculationSnapshotRepository;
import com.finora.loan.service.contract.ContractConsentResult;
import com.finora.loan.service.contract.LoanContractService;
import com.finora.loan.service.contract.LoanContractStateService;
import com.finora.loan.support.HashingService;
import java.util.Map;
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
    private final LoanContractStatusHistoryRepository historyRepository;
    private final LoanApplicationRepository applicationRepository;
    private final ScheduleCalculationSnapshotRepository scheduleRepository;
    private final LoanContractStateService stateService;
    private final LoanContractMapper mapper;
    private final HashingService hashingService;
    private final MockCurrentUserProvider currentUser;

    /** Một page Contract + một batch Application; document lớn không được tải vào response summary. */
    @Override
    @Transactional(readOnly = true)
    public PageResponse<LoanContractSummaryResponse> listMine(int page, int size) {
        Page<LoanContract> contracts = contractRepository.findByBorrowerId(
                currentUser.borrowerUserId(),
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
        contract.requireOwner(currentUser.borrowerUserId());
        LoanApplication application = applicationRepository.findById(contract.getApplicationId())
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Loan Application", "id", contract.getApplicationId()));
        ScheduleCalculationSnapshot schedule = scheduleRepository.findById(contract.getCalculationSnapshotId())
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Schedule Calculation Snapshot", "id", contract.getCalculationSnapshotId()));
        return mapper.toDetail(contract, application, schedule);
    }

    @Override
    public LoanContractActionResponse sign(
            String contractNumber,
            String idempotencyKey,
            SignLoanContractRequest request
    ) {
        String normalizedKey = idempotencyKey.trim();
        String requestHash = hashingService.sha256(new ConsentFingerprint("SIGN", request));
        ContractConsentResult result = executeWithDuplicateRecovery(
                contractNumber, normalizedKey, requestHash, ConsentAction.SIGN,
                () -> stateService.sign(
                        contractNumber, normalizedKey, requestHash, request, currentUser.borrowerUserId()));
        rejectExpired(result);
        log.info("Borrower đã ký Contract: contractNumber={}, actorId={}",
                contractNumber, currentUser.borrowerUserId());
        return mapper.toAction(result.contract());
    }

    @Override
    public LoanContractActionResponse decline(
            String contractNumber,
            String idempotencyKey,
            DeclineLoanContractRequest request
    ) {
        String normalizedKey = idempotencyKey.trim();
        String requestHash = hashingService.sha256(new ConsentFingerprint("DECLINE", request));
        ContractConsentResult result = executeWithDuplicateRecovery(
                contractNumber, normalizedKey, requestHash, ConsentAction.DECLINE,
                () -> stateService.decline(
                        contractNumber, normalizedKey, requestHash, request, currentUser.borrowerUserId()));
        rejectExpired(result);
        log.info("Borrower đã từ chối Contract: contractNumber={}, reasonCode={}, actorId={}",
                contractNumber, request.reasonCode(), currentUser.borrowerUserId());
        return mapper.toAction(result.contract());
    }

    @Override
    @Transactional(readOnly = true)
    public PageResponse<LoanContractHistoryResponse> history(String contractNumber, int page, int size) {
        LoanContract contract = contract(contractNumber);
        contract.requireOwner(currentUser.borrowerUserId());
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
                    contractNumber, idempotencyKey, requestHash, action, currentUser.borrowerUserId());
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

    private record ConsentFingerprint(String action, Object request) {
    }
}
