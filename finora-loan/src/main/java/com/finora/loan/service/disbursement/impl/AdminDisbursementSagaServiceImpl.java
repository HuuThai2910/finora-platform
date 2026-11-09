package com.finora.loan.service.disbursement.impl;
import com.finora.common.exception.BusinessException;
import com.finora.common.security.SecurityUtils;
import com.finora.loan.domain.disbursement.entity.DisbursementSaga;
import com.finora.loan.domain.disbursement.enums.DisbursementSagaStatus;
import com.finora.loan.dto.decision.response.ApplicationNumberResponse;
import com.finora.loan.dto.disbursement.response.AdminDisbursementSagaResponse;
import com.finora.loan.dto.response.PageResponse;
import com.finora.loan.repository.contract.LoanContractRepository;
import com.finora.loan.repository.disbursement.DisbursementSagaRepository;
import com.finora.loan.service.application.ApplicationNumberLookupService;
import com.finora.loan.service.disbursement.AdminDisbursementSagaService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.UUID;

/**
 * Màn sự cố giải ngân của admin (R2): xem saga theo trạng thái và đưa saga REPAIR_REQUIRED về ghi core lại sau khi vận hành
 * đã kiểm Fineract theo số hợp đồng. Không có thao tác nào ở đây chuyển tiền hay sửa dư nợ.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class AdminDisbursementSagaServiceImpl implements AdminDisbursementSagaService {
    private final DisbursementSagaRepository disbursementSagaRepository;
    private final ApplicationNumberLookupService applicationNumberLookupService;
    private final LoanContractRepository loanContractRepository;
    private final Clock clock;

    @Override
    @Transactional(readOnly = true)
    public PageResponse<AdminDisbursementSagaResponse> list(DisbursementSagaStatus status, int page, int size) {
        DisbursementSagaStatus statusFilter = status == null ? DisbursementSagaStatus.REPAIR_REQUIRED : status;
        var sagas = disbursementSagaRepository.findByStatusOrderByUpdatedAtAscIdAsc(statusFilter, PageRequest.of(page, size));
        Map<Long, ApplicationNumberResponse> references = applicationNumberLookupService.referencesOf(
                        sagas.getContent().stream().map(DisbursementSaga::getLoanApplicationId).toList())
                .stream().collect(Collectors.toMap(ApplicationNumberResponse::id, Function.identity()));
        Map<String, String> contractCodes = contractCodes(
                sagas.getContent().stream().map(DisbursementSaga::getContractNumber).toList());
        return PageResponse.from(sagas.map(saga -> AdminDisbursementSagaResponse.from(saga,
                references.get(saga.getLoanApplicationId()), contractCodes.get(saga.getContractNumber()))));
    }

    @Override
    @Transactional(readOnly = true)
    public PageResponse<AdminDisbursementSagaResponse> listByApplication(
            Long loanApplicationId, DisbursementSagaStatus status) {
        DisbursementSagaStatus statusFilter = status == null ? DisbursementSagaStatus.REPAIR_REQUIRED : status;
        List<AdminDisbursementSagaResponse> rows = disbursementSagaRepository.findByLoanApplicationId(loanApplicationId)
                .filter(saga -> saga.getStatus() == statusFilter)
                .map(saga -> List.of(AdminDisbursementSagaResponse.from(saga,
                        applicationNumberLookupService.referencesOf(List.of(saga.getLoanApplicationId())).stream()
                                .findFirst().orElse(null),
                        contractCodes(List.of(saga.getContractNumber())).get(saga.getContractNumber()))))
                .orElse(List.of());
        return new PageResponse<>(rows, 0, Math.max(rows.size(), 1), rows.size());
    }

    @Override
    @Transactional
    public AdminDisbursementSagaResponse requeue(UUID sagaId, String note) {
        DisbursementSaga saga = disbursementSagaRepository.findBySagaIdForUpdate(sagaId)
                .orElseThrow(() -> new BusinessException(
                        HttpStatus.NOT_FOUND, "DISBURSEMENT_SAGA_NOT_FOUND", "Không tìm thấy saga giải ngân"));
        if (saga.getStatus() != DisbursementSagaStatus.REPAIR_REQUIRED) {
            throw new BusinessException(HttpStatus.CONFLICT, "DISBURSEMENT_SAGA_NOT_REPAIRABLE",
                    "Chỉ saga đang chờ vận hành (REPAIR_REQUIRED) mới được ghi core lại");
        }
        String actorId = SecurityUtils.getCurrentUserId();
        saga.requeueAfterRepair(actorId, note, clock.instant());
        log.warn("Admin đưa saga giải ngân về ghi core lại: sagaId={}, contract={}, actor={}, repairCount={}",
                saga.getSagaId(), saga.getContractNumber(), actorId, saga.getRepairCount());
        return AdminDisbursementSagaResponse.from(saga, applicationNumberLookupService
                        .referencesOf(List.of(saga.getLoanApplicationId())).stream().findFirst().orElse(null),
                contractCodes(List.of(saga.getContractNumber())).get(saga.getContractNumber()));
    }

    /** Mã hiển thị {@code HĐ-…} theo mã tra cứu, đọc projection nên không tải văn bản hợp đồng. */
    private Map<String, String> contractCodes(List<String> contractNumbers) {
        return contractNumbers.isEmpty() ? Map.of() : loanContractRepository.findContractCodes(contractNumbers).stream()
                .collect(Collectors.toMap(LoanContractRepository.ContractCodeView::getContractNumber,
                        LoanContractRepository.ContractCodeView::getContractCode));
    }
}
