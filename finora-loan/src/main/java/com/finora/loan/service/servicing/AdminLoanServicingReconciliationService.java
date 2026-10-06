package com.finora.loan.service.servicing;

import com.finora.common.security.SecurityUtils;
import com.finora.loan.domain.servicing.FinoraLoan;
import com.finora.loan.domain.servicing.FinoraLoanStatus;
import com.finora.loan.domain.servicing.LoanServicingProjection;
import com.finora.loan.domain.servicing.LoanReconciliationIncident;
import com.finora.loan.domain.servicing.ReconciliationIncidentStatus;
import com.finora.loan.domain.servicing.ReconciliationIncidentType;
import com.finora.loan.dto.common.PageResponse;
import com.finora.loan.dto.servicing.response.AdminLoanServicingReconciliationResponse;
import com.finora.loan.dto.servicing.response.LoanReconciliationIncidentResponse;
import com.finora.loan.exception.LoanBusinessException;
import com.finora.loan.repository.servicing.FinoraLoanRepository;
import com.finora.loan.repository.servicing.LoanServicingProjectionRepository;
import com.finora.loan.repository.servicing.LoanReconciliationIncidentRepository;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class AdminLoanServicingReconciliationService {
    private final FinoraLoanRepository loans;
    private final LoanServicingProjectionRepository projections;
    private final LoanReconciliationIncidentRepository incidents;
    private final LoanServicingSyncService syncService;

    /** Danh sách projection stale dùng hai query cố định, không gọi Fineract theo từng dòng. */
    @Transactional(readOnly = true)
    public PageResponse<AdminLoanServicingReconciliationResponse> listStale(int page, int size) {
        SecurityUtils.requireAdmin();
        Page<LoanServicingProjection> result = projections.findByStaleTrueOrderByUpdatedAtAscIdAsc(
                PageRequest.of(page, size));
        Map<Long, FinoraLoan> byId = loans.findAllById(result.getContent().stream()
                        .map(LoanServicingProjection::getFinoraLoanId).toList())
                .stream().collect(Collectors.toMap(FinoraLoan::getId, Function.identity()));
        return new PageResponse<>(result.getContent().stream()
                .map(projection -> response(requiredLoan(byId.get(projection.getFinoraLoanId())), projection))
                .toList(), result.getNumber(), result.getSize(), result.getTotalElements());
    }

    /** Gọi GET Fineract ngoài transaction; lặp lại an toàn và không phát sinh repayment/disbursement. */
    public AdminLoanServicingReconciliationResponse reconcile(String loanNumber) {
        SecurityUtils.requireAdmin();
        FinoraLoan loan = loans.findByLoanNumber(loanNumber).orElseThrow(() -> notFound(loanNumber));
        if (loan.getStatus() != FinoraLoanStatus.ACTIVE && loan.getStatus() != FinoraLoanStatus.DEFAULTED) {
            throw new LoanBusinessException(HttpStatus.CONFLICT, "LOAN_NOT_RECONCILABLE",
                    "Chỉ khoản vay ACTIVE hoặc DEFAULTED mới được đối soát servicing");
        }
        syncService.sync(loan.getId());
        FinoraLoan refreshed = loans.findByLoanNumber(loanNumber).orElseThrow(() -> notFound(loanNumber));
        LoanServicingProjection projection = projections.findByFinoraLoanId(refreshed.getId())
                .orElseThrow(() -> new IllegalStateException("Khoản vay thiếu servicing projection"));
        return response(refreshed, projection);
    }

    /** Queue incident chỉ đọc; admin khắc phục nguyên nhân rồi chạy reconcile theo loanNumber. */
    @Transactional(readOnly = true)
    public PageResponse<LoanReconciliationIncidentResponse> listIncidents(
            ReconciliationIncidentStatus status, ReconciliationIncidentType type, int page, int size) {
        SecurityUtils.requireAdmin();
        ReconciliationIncidentStatus selected = status == null ? ReconciliationIncidentStatus.OPEN : status;
        Page<LoanReconciliationIncident> result = type == null
                ? incidents.findByStatusOrderByUpdatedAtAscIdAsc(selected, PageRequest.of(page, size))
                : incidents.findByStatusAndTypeOrderByUpdatedAtAscIdAsc(
                        selected, type, PageRequest.of(page, size));
        return PageResponse.from(result.map(AdminLoanServicingReconciliationService::incidentResponse));
    }

    private static AdminLoanServicingReconciliationResponse response(
            FinoraLoan loan, LoanServicingProjection projection) {
        return new AdminLoanServicingReconciliationResponse(
                loan.getLoanNumber(), loan.getApplicationNumber(), loan.getFineractLoanId(), loan.getStatus(),
                projection.getFineractStatusCode(), projection.getTotalOutstanding(), projection.getOverdueAmount(),
                projection.getDaysPastDue(), projection.getSource(), projection.getDataAsOf(),
                projection.getLastSyncedAt(), projection.isStale());
    }

    private static LoanReconciliationIncidentResponse incidentResponse(LoanReconciliationIncident value) {
        return new LoanReconciliationIncidentResponse(value.getIncidentId(), value.getLoanNumber(), value.getType(),
                value.getStatus(), value.getExpectedValue(), value.getActualValue(), value.getOccurrenceCount(),
                value.getFirstDetectedAt(), value.getLastDetectedAt(), value.getResolvedAt(),
                value.getResolutionCode(), value.getResolvedBy());
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
