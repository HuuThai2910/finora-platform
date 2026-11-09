package com.finora.loan.service.servicing.impl;

import com.finora.common.security.SecurityUtils;
import com.finora.common.security.ops.OpsFunction;
import com.finora.loan.domain.servicing.entity.FinoraLoan;
import com.finora.loan.domain.servicing.entity.LoanReconciliationIncident;
import com.finora.loan.domain.servicing.entity.LoanServicingProjection;
import com.finora.loan.domain.servicing.enums.FinoraLoanStatus;
import com.finora.loan.domain.servicing.enums.ReconciliationIncidentStatus;
import com.finora.loan.domain.servicing.enums.ReconciliationIncidentType;
import com.finora.loan.dto.response.PageResponse;
import com.finora.loan.dto.servicing.response.AdminLoanServicingReconciliationResponse;
import com.finora.loan.dto.servicing.response.LoanReconciliationIncidentResponse;
import com.finora.loan.exception.LoanBusinessException;
import com.finora.loan.repository.servicing.FinoraLoanRepository;
import com.finora.loan.repository.servicing.LoanReconciliationIncidentRepository;
import com.finora.loan.repository.servicing.LoanServicingProjectionRepository;
import com.finora.loan.service.application.ApplicationNumberLookupService;
import com.finora.loan.service.servicing.AdminLoanServicingReconciliationService;
import com.finora.loan.service.servicing.LoanServicingSyncService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Màn đối soát servicing của admin: projection stale, incident đối soát và chạy lại đồng bộ một khoản vay từ
 * Fineract.
 *
 * <p>Quyền chức năng {@code RECONCILIATION} được kiểm ngay trong service.</p>
 */
@Service
@RequiredArgsConstructor
public class AdminLoanServicingReconciliationServiceImpl implements AdminLoanServicingReconciliationService {
    private final FinoraLoanRepository finoraLoanRepository;
    private final LoanServicingProjectionRepository loanServicingProjectionRepository;
    private final LoanReconciliationIncidentRepository loanReconciliationIncidentRepository;
    private final LoanServicingSyncService loanServicingSyncService;
    private final ApplicationNumberLookupService applicationNumberLookupService;

    @Override
    @Transactional(readOnly = true)
    public PageResponse<AdminLoanServicingReconciliationResponse> listStale(int page, int size) {
        SecurityUtils.requirePermission(OpsFunction.RECONCILIATION.view());
        Page<LoanServicingProjection> result = loanServicingProjectionRepository
                .findByStaleTrueOrderByUpdatedAtAscIdAsc(PageRequest.of(page, size));
        Map<Long, FinoraLoan> loansById = finoraLoanRepository.findAllById(result.getContent().stream()
                        .map(LoanServicingProjection::getFinoraLoanId).toList())
                .stream().collect(Collectors.toMap(FinoraLoan::getId, Function.identity()));
        Map<Long, String> codes = applicationCodes(loansById.values().stream()
                .map(FinoraLoan::getLoanApplicationId).toList());
        return new PageResponse<>(result.getContent().stream()
                .map(projection -> {
                    FinoraLoan loan = requiredLoan(loansById.get(projection.getFinoraLoanId()));
                    return response(loan, projection, codes.get(loan.getLoanApplicationId()));
                })
                .toList(), result.getNumber(), result.getSize(), result.getTotalElements());
    }

    @Override
    @Transactional(readOnly = true)
    public AdminLoanServicingReconciliationResponse status(String loanNumber) {
        SecurityUtils.requirePermission(OpsFunction.RECONCILIATION.view());
        FinoraLoan loan = finoraLoanRepository.findByLoanNumber(loanNumber).orElseThrow(() -> notFound(loanNumber));
        LoanServicingProjection projection = loanServicingProjectionRepository.findByFinoraLoanId(loan.getId())
                .orElseThrow(() -> new LoanBusinessException(HttpStatus.NOT_FOUND, "LOAN_SERVICING_NOT_FOUND",
                        "Khoản vay " + loanNumber + " chưa có số liệu đồng bộ từ hệ thống lõi"));
        return response(loan, projection,
                applicationCodes(List.of(loan.getLoanApplicationId())).get(loan.getLoanApplicationId()));
    }

    @Override
    public AdminLoanServicingReconciliationResponse reconcile(String loanNumber) {
        SecurityUtils.requirePermission(OpsFunction.RECONCILIATION.action());
        FinoraLoan loan = finoraLoanRepository.findByLoanNumber(loanNumber).orElseThrow(() -> notFound(loanNumber));
        if (loan.getStatus() != FinoraLoanStatus.ACTIVE && loan.getStatus() != FinoraLoanStatus.DEFAULTED) {
            throw new LoanBusinessException(HttpStatus.CONFLICT, "LOAN_NOT_RECONCILABLE",
                    "Chỉ khoản vay ACTIVE hoặc DEFAULTED mới được đối soát servicing");
        }
        loanServicingSyncService.sync(loan.getId());
        FinoraLoan refreshed = finoraLoanRepository.findByLoanNumber(loanNumber)
                .orElseThrow(() -> notFound(loanNumber));
        LoanServicingProjection projection = loanServicingProjectionRepository.findByFinoraLoanId(refreshed.getId())
                .orElseThrow(() -> new IllegalStateException("Khoản vay thiếu servicing projection"));
        return response(refreshed, projection,
                applicationCodes(List.of(refreshed.getLoanApplicationId())).get(refreshed.getLoanApplicationId()));
    }

    /** Mã hiển thị {@code HS-…} theo ID hồ sơ; hồ sơ không tra được thì vắng trong kết quả. */
    private Map<Long, String> applicationCodes(List<Long> applicationIds) {
        Map<Long, String> codes = new HashMap<>();
        applicationNumberLookupService.referencesOf(applicationIds).forEach(reference -> {
            if (reference.applicationCode() != null) {
                codes.put(reference.id(), reference.applicationCode());
            }
        });
        return codes;
    }

    @Override
    @Transactional(readOnly = true)
    public PageResponse<LoanReconciliationIncidentResponse> listIncidents(
            ReconciliationIncidentStatus status, ReconciliationIncidentType type, int page, int size) {
        SecurityUtils.requirePermission(OpsFunction.RECONCILIATION.view());
        ReconciliationIncidentStatus statusFilter = status == null ? ReconciliationIncidentStatus.OPEN : status;
        Page<LoanReconciliationIncident> result = type == null
                ? loanReconciliationIncidentRepository.findByStatusOrderByUpdatedAtAscIdAsc(
                        statusFilter, PageRequest.of(page, size))
                : loanReconciliationIncidentRepository.findByStatusAndTypeOrderByUpdatedAtAscIdAsc(
                        statusFilter, type, PageRequest.of(page, size));
        return PageResponse.from(result.map(AdminLoanServicingReconciliationServiceImpl::incidentResponse));
    }

    @Override
    @Transactional(readOnly = true)
    public PageResponse<LoanReconciliationIncidentResponse> listIncidentsByLoan(
            String loanNumber, ReconciliationIncidentStatus status, int page, int size) {
        SecurityUtils.requirePermission(OpsFunction.RECONCILIATION.view());
        ReconciliationIncidentStatus statusFilter = status == null ? ReconciliationIncidentStatus.OPEN : status;
        return PageResponse.from(loanReconciliationIncidentRepository
                .findByLoanNumberAndStatusOrderByUpdatedAtAscIdAsc(loanNumber.trim(), statusFilter,
                        PageRequest.of(page, size))
                .map(AdminLoanServicingReconciliationServiceImpl::incidentResponse));
    }

    private static AdminLoanServicingReconciliationResponse response(
            FinoraLoan loan, LoanServicingProjection projection, String applicationCode) {
        return new AdminLoanServicingReconciliationResponse(
                loan.getLoanNumber(), loan.getApplicationNumber(), loan.getFineractLoanId(), loan.getStatus(),
                projection.getFineractStatusCode(), projection.getTotalOutstanding(), projection.getOverdueAmount(),
                projection.getDaysPastDue(), projection.getSource(), projection.getDataAsOf(),
                projection.getLastSyncedAt(), projection.isStale(), applicationCode);
    }

    private static LoanReconciliationIncidentResponse incidentResponse(LoanReconciliationIncident incident) {
        return new LoanReconciliationIncidentResponse(incident.getIncidentId(), incident.getLoanNumber(),
                incident.getType(), incident.getStatus(), incident.getExpectedValue(), incident.getActualValue(),
                incident.getOccurrenceCount(), incident.getFirstDetectedAt(), incident.getLastDetectedAt(),
                incident.getResolvedAt(), incident.getResolutionCode(), incident.getResolvedBy());
    }

    private static FinoraLoan requiredLoan(FinoraLoan loan) {
        if (loan == null) throw new IllegalStateException("Servicing projection không có khoản vay nguồn");
        return loan;
    }

    private static LoanBusinessException notFound(String loanNumber) {
        return new LoanBusinessException(HttpStatus.NOT_FOUND, "LOAN_NOT_FOUND",
                "Không tìm thấy khoản vay " + loanNumber);
    }
}
