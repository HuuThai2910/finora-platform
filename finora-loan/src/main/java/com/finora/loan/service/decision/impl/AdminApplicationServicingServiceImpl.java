package com.finora.loan.service.decision.impl;

import com.finora.loan.domain.collection.enums.CollectionCaseStatus;
import com.finora.loan.domain.disbursement.entity.DisbursementSaga;
import com.finora.loan.domain.disbursement.enums.DisbursementSagaStatus;
import com.finora.loan.domain.restructure.enums.LoanRescheduleStatus;
import com.finora.loan.domain.servicing.entity.FinoraLoan;
import com.finora.loan.domain.servicing.enums.ApplicationServicingFlag;
import com.finora.loan.domain.servicing.enums.ReconciliationIncidentStatus;
import com.finora.loan.domain.servicing.model.ApplicationServicingSummary;
import com.finora.loan.repository.collection.LoanCollectionCaseRepository;
import com.finora.loan.repository.disbursement.DisbursementSagaRepository;
import com.finora.loan.repository.restructure.LoanRescheduleRequestRepository;
import com.finora.loan.repository.servicing.FinoraLoanRepository;
import com.finora.loan.repository.servicing.LoanReconciliationIncidentRepository;
import com.finora.loan.repository.servicing.LoanServicingProjectionRepository;
import com.finora.loan.service.decision.AdminApplicationServicingService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Tình trạng sau giải ngân theo trang hồ sơ: tối đa sáu query cho cả trang (khoản vay, saga, thu hồi mở, cơ cấu chờ
 * duyệt, sai lệch mở, số liệu cũ), không tăng theo số dòng.
 */
@Service
@RequiredArgsConstructor
public class AdminApplicationServicingServiceImpl implements AdminApplicationServicingService {
    private final FinoraLoanRepository finoraLoanRepository;
    private final DisbursementSagaRepository disbursementSagaRepository;
    private final LoanCollectionCaseRepository loanCollectionCaseRepository;
    private final LoanRescheduleRequestRepository loanRescheduleRequestRepository;
    private final LoanReconciliationIncidentRepository loanReconciliationIncidentRepository;
    private final LoanServicingProjectionRepository loanServicingProjectionRepository;

    @Override
    @Transactional(readOnly = true)
    public Map<Long, ApplicationServicingSummary> summariesOf(Collection<Long> applicationIds) {
        if (applicationIds == null || applicationIds.isEmpty()) {
            return Map.of();
        }
        Map<Long, DisbursementSaga> sagas = disbursementSagaRepository.findByLoanApplicationIdIn(applicationIds)
                .stream().collect(Collectors.toMap(DisbursementSaga::getLoanApplicationId, Function.identity(),
                        (first, second) -> first));
        Map<Long, FinoraLoan> loans = finoraLoanRepository.findByLoanApplicationIdIn(applicationIds).stream()
                .collect(Collectors.toMap(FinoraLoan::getLoanApplicationId, Function.identity()));
        Set<Long> loanIds = loans.values().stream().map(FinoraLoan::getId).collect(Collectors.toSet());
        Set<Long> overdue = new HashSet<>();
        Set<Long> reschedule = new HashSet<>();
        Set<Long> incident = new HashSet<>();
        if (!loanIds.isEmpty()) {
            overdue.addAll(loanCollectionCaseRepository.findLoanIdsWithStatus(CollectionCaseStatus.OPEN, loanIds));
            reschedule.addAll(loanRescheduleRequestRepository.findLoanIdsWithStatus(
                    LoanRescheduleStatus.PENDING_REVIEW, loanIds));
            incident.addAll(loanReconciliationIncidentRepository.findLoanIdsWithStatus(
                    ReconciliationIncidentStatus.OPEN, loanIds));
            incident.addAll(loanServicingProjectionRepository.findStaleLoanIds(loanIds));
        }
        Map<Long, ApplicationServicingSummary> result = new HashMap<>();
        for (Long applicationId : applicationIds) {
            DisbursementSaga saga = sagas.get(applicationId);
            FinoraLoan loan = loans.get(applicationId);
            if (saga == null && loan == null) {
                continue;
            }
            List<ApplicationServicingFlag> flags = new ArrayList<>();
            Long loanId = loan == null ? null : loan.getId();
            if (loanId != null && overdue.contains(loanId)) flags.add(ApplicationServicingFlag.OVERDUE);
            if (loanId != null && reschedule.contains(loanId)) flags.add(ApplicationServicingFlag.RESCHEDULE_PENDING);
            boolean sagaRepair = saga != null && saga.getStatus() == DisbursementSagaStatus.REPAIR_REQUIRED;
            if (sagaRepair || loanId != null && incident.contains(loanId)) flags.add(ApplicationServicingFlag.INCIDENT);
            result.put(applicationId, new ApplicationServicingSummary(
                    loan == null ? null : loan.getLoanNumber(),
                    loan == null ? null : loan.getStatus(),
                    saga == null ? null : saga.getStatus(),
                    flags));
        }
        return result;
    }

    @Override
    @Transactional(readOnly = true)
    public ApplicationServicingSummary summaryOf(Long applicationId) {
        if (applicationId == null) {
            return ApplicationServicingSummary.NONE;
        }
        return summariesOf(List.of(applicationId)).getOrDefault(applicationId, ApplicationServicingSummary.NONE);
    }
}
