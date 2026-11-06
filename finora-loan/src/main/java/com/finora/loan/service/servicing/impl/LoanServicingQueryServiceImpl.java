package com.finora.loan.service.servicing.impl;

import com.finora.common.security.SecurityUtils;
import com.finora.loan.domain.contract.entity.LoanContract;
import com.finora.loan.domain.core.entity.ScheduleCalculationSnapshot;
import com.finora.loan.domain.disbursement.enums.DisbursementSagaStatus;
import com.finora.loan.domain.servicing.entity.FinoraLoan;
import com.finora.loan.domain.servicing.entity.LoanServicingProjection;
import com.finora.loan.dto.response.PageResponse;
import com.finora.loan.dto.servicing.response.LoanRepaymentScheduleResponse;
import com.finora.loan.dto.servicing.response.LoanServicingSummaryResponse;
import com.finora.loan.dto.servicing.response.PendingLoanActivationResponse;
import com.finora.loan.exception.LoanBusinessException;
import com.finora.loan.mapper.core.ScheduleCalculationSnapshotMapper;
import com.finora.loan.repository.application.LoanApplicationRepository;
import com.finora.loan.repository.contract.LoanContractRepository;
import com.finora.loan.repository.core.ScheduleCalculationSnapshotRepository;
import com.finora.loan.repository.disbursement.DisbursementSagaRepository;
import com.finora.loan.repository.servicing.FinoraLoanRepository;
import com.finora.loan.repository.servicing.LoanServicingProjectionRepository;
import com.finora.loan.service.servicing.LoanServicingQueryService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Đọc khoản vay đang phục vụ của người vay hiện tại: danh sách, chi tiết, lịch trả và khoản đang chờ kích hoạt.
 *
 * <p>Chỉ đọc projection và snapshot đã lưu ở Loan, không gọi Fineract.</p>
 */
@Service
@RequiredArgsConstructor
public class LoanServicingQueryServiceImpl implements LoanServicingQueryService {

    private final FinoraLoanRepository finoraLoanRepository;
    private final LoanServicingProjectionRepository loanServicingProjectionRepository;
    private final LoanContractRepository loanContractRepository;
    private final ScheduleCalculationSnapshotRepository scheduleCalculationSnapshotRepository;
    private final ScheduleCalculationSnapshotMapper scheduleCalculationSnapshotMapper;
    private final DisbursementSagaRepository disbursementSagaRepository;
    private final LoanApplicationRepository loanApplicationRepository;

    private static final EnumSet<DisbursementSagaStatus> BORROWER_VISIBLE_PENDING_STATUSES = EnumSet.of(
            DisbursementSagaStatus.WAITING_PAYMENT,
            DisbursementSagaStatus.CORE_BOOKING_PENDING,
            DisbursementSagaStatus.CORE_BOOKING,
            DisbursementSagaStatus.RETRY_PENDING,
            DisbursementSagaStatus.REPAIR_REQUIRED);

    @Override
    @Transactional(readOnly = true)
    public PageResponse<LoanServicingSummaryResponse> listMine(int page, int size) {
        Page<FinoraLoan> loans = finoraLoanRepository.findByBorrowerIdOrderByDisbursedAtDesc(
                SecurityUtils.getCurrentUserId(), PageRequest.of(page, size));
        Map<Long, LoanServicingProjection> projections = loanServicingProjectionRepository
                .findByFinoraLoanIdIn(loans.getContent().stream().map(FinoraLoan::getId).toList())
                .stream().collect(Collectors.toMap(LoanServicingProjection::getFinoraLoanId, Function.identity()));
        return new PageResponse<>(loans.getContent().stream()
                .map(loan -> summary(loan, requiredProjection(projections.get(loan.getId()))))
                .toList(), loans.getNumber(), loans.getSize(), loans.getTotalElements());
    }

    @Override
    @Transactional(readOnly = true)
    public List<PendingLoanActivationResponse> pendingMine() {
        var sagas = disbursementSagaRepository.findByBorrowerIdAndStatusInOrderByCreatedAtDescIdDesc(
                SecurityUtils.getCurrentUserId(), BORROWER_VISIBLE_PENDING_STATUSES);
        Map<Long, String> applicationNumbers = loanApplicationRepository.findAllById(
                        sagas.stream().map(saga -> saga.getLoanApplicationId()).toList())
                .stream().collect(Collectors.toMap(application -> application.getId(),
                        application -> application.getApplicationNumber()));
        return sagas.stream().map(saga -> new PendingLoanActivationResponse(
                saga.getLoanApplicationId(),
                applicationNumbers.getOrDefault(saga.getLoanApplicationId(), "#" + saga.getLoanApplicationId()),
                saga.getContractNumber(),
                saga.getAmount(),
                saga.getCurrency(),
                saga.getStatus(),
                saga.getUpdatedAt())).toList();
    }

    @Override
    @Transactional(readOnly = true)
    public LoanServicingSummaryResponse getMine(String loanNumber) {
        FinoraLoan loan = ownedLoan(loanNumber);
        return summary(loan, projection(loan));
    }

    @Override
    @Transactional(readOnly = true)
    public LoanRepaymentScheduleResponse schedule(String loanNumber) {
        FinoraLoan loan = ownedLoan(loanNumber);
        LoanServicingProjection projection = projection(loan);
        LoanContract contract = loanContractRepository.findByContractNumber(loan.getContractNumber())
                .orElseThrow(() -> new IllegalStateException("Khoản vay thiếu hợp đồng nguồn"));
        ScheduleCalculationSnapshot snapshot = scheduleCalculationSnapshotRepository
                .findById(contract.getCalculationSnapshotId())
                .orElseThrow(() -> new IllegalStateException("Khoản vay thiếu snapshot lịch trả"));
        return new LoanRepaymentScheduleResponse(
                summary(loan, projection),
                scheduleCalculationSnapshotMapper.periods(snapshot),
                projection.getSource(),
                projection.getDataAsOf(),
                projection.isStale());
    }

    private FinoraLoan ownedLoan(String loanNumber) {
        FinoraLoan loan = finoraLoanRepository.findByLoanNumber(loanNumber).orElseThrow(() ->
                new LoanBusinessException(HttpStatus.NOT_FOUND, "LOAN_NOT_FOUND", "Không tìm thấy khoản vay"));
        if (!loan.getBorrowerId().equals(SecurityUtils.getCurrentUserId())) {
            throw LoanBusinessException.forbidden("LOAN_ACCESS_DENIED", "Khoản vay không thuộc người dùng hiện tại");
        }
        return loan;
    }

    private LoanServicingProjection projection(FinoraLoan loan) {
        return requiredProjection(loanServicingProjectionRepository.findByFinoraLoanId(loan.getId()).orElse(null));
    }

    private static LoanServicingProjection requiredProjection(LoanServicingProjection projection) {
        if (projection == null) {
            throw new IllegalStateException("Khoản vay thiếu servicing projection");
        }
        return projection;
    }

    private static LoanServicingSummaryResponse summary(FinoraLoan loan, LoanServicingProjection projection) {
        return new LoanServicingSummaryResponse(
                loan.getLoanNumber(), loan.getApplicationNumber(), loan.getContractNumber(), loan.getStatus(),
                loan.getPrincipalAmount(), projection.getPrincipalOutstanding(), projection.getTotalOutstanding(),
                projection.getOverdueAmount(), projection.getDaysPastDue(), projection.getNextDueDate(),
                projection.getNextDueAmount(), projection.getMaturityDate(), loan.getCurrency(),
                projection.getSource(), projection.getDataAsOf(), projection.getLastSyncedAt(), projection.isStale());
    }
}
