package com.finora.loan.service.restructure.impl;

import com.finora.loan.config.LoanRescheduleProperties;
import com.finora.loan.domain.restructure.entity.*;
import com.finora.loan.domain.restructure.enums.*;
import com.finora.loan.domain.servicing.entity.FinoraLoan;
import com.finora.loan.domain.servicing.entity.LoanServicingProjection;
import com.finora.loan.domain.servicing.enums.FinoraLoanStatus;
import com.finora.loan.domain.servicing.model.LoanServicingSnapshot;
import com.finora.loan.integration.fineract.FineractIntegrationException;
import com.finora.loan.integration.fineract.client.FineractRescheduleGateway;
import com.finora.loan.integration.fineract.client.FineractServicingGateway;
import com.finora.loan.messaging.event.LoanDelinquencyChangedEventData;
import com.finora.loan.messaging.event.LoanRescheduledEventData;
import com.finora.loan.repository.restructure.LoanRescheduleRequestRepository;
import com.finora.loan.repository.servicing.FinoraLoanRepository;
import com.finora.loan.repository.servicing.LoanServicingProjectionRepository;
import com.finora.loan.repository.servicing.LoanServicingScheduleVersionRepository;
import com.finora.loan.domain.servicing.entity.LoanServicingScheduleVersion;
import com.finora.loan.mapper.core.ScheduleCalculationSnapshotMapper;
import com.finora.loan.service.collection.CollectionCaseService;
import com.finora.loan.service.outbox.OutboxService;
import com.finora.loan.service.restructure.LoanRescheduleExecutionService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/**
 * Thực hiện yêu cầu cơ cấu đã được nhà đầu tư đồng ý trên Fineract: tạo yêu cầu đổi lịch, duyệt, rồi cập nhật
 * projection và phát event.
 * <p>
 * Lời gọi Fineract nằm ngoài transaction; claim và ghi kết quả là các transaction ngắn qua {@link TransactionTemplate}.
 * Marker {@code [FINORA:requestId]} giúp lần chạy lại sau timeout tìm thấy yêu cầu đã tạo thay vì tạo thêm; lỗi Fineract
 * được ghi kèm backoff để worker thử lại.
 */
@Service
@RequiredArgsConstructor
public class LoanRescheduleExecutionServiceImpl implements LoanRescheduleExecutionService {
    private final LoanRescheduleRequestRepository loanRescheduleRequestRepository;
    private final FinoraLoanRepository finoraLoanRepository;
    private final LoanServicingProjectionRepository loanServicingProjectionRepository;
    private final LoanServicingScheduleVersionRepository loanServicingScheduleVersionRepository;
    private final ScheduleCalculationSnapshotMapper scheduleCalculationSnapshotMapper;
    private final FineractRescheduleGateway fineractRescheduleGateway;
    private final FineractServicingGateway fineractServicingGateway;
    private final LoanRescheduleProperties loanRescheduleProperties;
    private final OutboxService outboxService;
    private final CollectionCaseService collectionCaseService;
    private final Clock clock;
    private final TransactionTemplate transactionTemplate;

    @Override
    public List<Long> dueIds() {
        Instant now = clock.instant();
        return transactionTemplate.execute(status -> loanRescheduleRequestRepository.findDueIds(
                List.of(LoanRescheduleStatus.CREATE_PENDING, LoanRescheduleStatus.APPROVAL_PENDING,
                        LoanRescheduleStatus.RECONCILIATION_REQUIRED),
                List.of(LoanRescheduleStatus.CREATING, LoanRescheduleStatus.APPROVING),
                now, now.minus(loanRescheduleProperties.processingLease()),
                PageRequest.of(0, loanRescheduleProperties.batchSize())));
    }

    @Override
    public void execute(Long id) {
        Work work = transactionTemplate.execute(status -> claim(id));
        if (work == null) return;
        try {
            if (work.step() == LoanRescheduleStep.CREATE) executeCreate(work);
            else executeApprove(work);
        } catch (FineractIntegrationException exception) {
            transactionTemplate.executeWithoutResult(status -> fail(work.requestId(), exception.getCode(),
                    exception.isRetryable()));
        } catch (RuntimeException exception) {
            transactionTemplate.executeWithoutResult(status -> fail(work.requestId(),
                    "RESTRUCTURE_UNEXPECTED_ERROR", false));
        }
    }

    private Work claim(Long id) {
        LoanRescheduleRequest request = loanRescheduleRequestRepository.findByIdForUpdate(id).orElse(null);
        Instant now = clock.instant();
        if (request == null || !request.due(now, loanRescheduleProperties.processingLease())) return null;
        LoanRescheduleStep step = request.claim(now, loanRescheduleProperties.processingLease());
        if (step == null) return null;
        FinoraLoan loan = finoraLoanRepository.findById(request.getFinoraLoanId()).orElseThrow();
        return new Work(request.getRequestId(), loan.getId(), loan.getLoanApplicationId(), loan.getLoanNumber(),
                loan.getBorrowerId(), loan.getFineractLoanId(), step, request.getRequestType(),
                request.getRescheduleFromDate(), request.getAdjustedDueDate(), request.getExtraTerms(),
                request.getFineractRescheduleId());
    }

    /** Reconcile marker trước create để timeout hoặc restart không tạo hai yêu cầu ở Fineract. */
    private void executeCreate(Work work) {
        String marker = marker(work.requestId());
        FineractRescheduleGateway.CoreReschedule core = fineractRescheduleGateway
                .findByLoanAndMarker(work.fineractLoanId(), marker)
                .orElseGet(() -> fineractRescheduleGateway.create(new FineractRescheduleGateway.CreateRescheduleCommand(
                        work.fineractLoanId(), work.requestType(), work.rescheduleFromDate(), work.adjustedDueDate(),
                        work.extraTerms(), loanRescheduleProperties.requireFineractReasonId(), marker,
                        LocalDate.now(clock))));
        transactionTemplate.executeWithoutResult(status -> markCoreCreated(work.requestId(), core.id()));
    }

    /** Nếu approve đã thành công trước timeout, chỉ đọc lại snapshot; không gửi command lần hai. */
    private void executeApprove(Work work) {
        FineractRescheduleGateway.CoreReschedule core = fineractRescheduleGateway.read(work.coreRescheduleId());
        if (core.rejected()) {
            throw new FineractIntegrationException("FINERACT_RESCHEDULE_REJECTED",
                    "Fineract đã từ chối yêu cầu cơ cấu", false, null);
        }
        if (!core.approved()) {
            fineractRescheduleGateway.approve(work.coreRescheduleId(), LocalDate.now(clock));
        }
        LoanServicingSnapshot snapshot = fineractServicingGateway.read(work.fineractLoanId(), LocalDate.now(clock));
        transactionTemplate.executeWithoutResult(status -> complete(work.requestId(), snapshot));
    }

    private void markCoreCreated(java.util.UUID requestId, Long coreId) {
        LoanRescheduleRequest request = loanRescheduleRequestRepository.findByRequestIdForUpdate(requestId)
                .orElseThrow();
        if (request.getStatus() == LoanRescheduleStatus.CREATING) request.coreCreated(coreId, clock.instant());
    }

    private void complete(java.util.UUID requestId, LoanServicingSnapshot snapshot) {
        LoanRescheduleRequest request = loanRescheduleRequestRepository.findByRequestIdForUpdate(requestId)
                .orElseThrow();
        if (request.getStatus() != LoanRescheduleStatus.APPROVING) return;
        FinoraLoan loan = finoraLoanRepository.findByIdForUpdate(request.getFinoraLoanId()).orElseThrow();
        LoanServicingProjection projection = loanServicingProjectionRepository
                .findByFinoraLoanIdForUpdate(loan.getId()).orElseThrow();
        Instant now = clock.instant();
        LocalDate previousMaturity = request.getOriginalMaturityDate();
        int previousDpd = projection.getDaysPastDue();
        projection.applyCoreSnapshot(snapshot, now);
        if (snapshot.periods().isEmpty()) {
            throw new IllegalStateException("Fineract không trả lịch mới sau khi duyệt cơ cấu");
        }
        int scheduleVersion = loanServicingScheduleVersionRepository.maximumVersion(loan.getId()) + 1;
        loanServicingScheduleVersionRepository.save(LoanServicingScheduleVersion.create(
                loan.getId(), request.getRequestId(), scheduleVersion,
                scheduleCalculationSnapshotMapper.periodsJson(snapshot.periods()), now));
        loan.completeRestructuring(now);
        CollectionCaseService.DebtGroupChange groups = collectionCaseService.apply(loan, projection, previousDpd, now);
        request.complete(snapshot.maturityDate(), now);
        // Lịch mới có thể đổi số ngày quá hạn (dời ngày đến hạn). Lần đồng bộ sau thấy projection đã cập nhật nên không
        // phát lại, vì vậy báo Investment/CIC ngay tại đây như một lần đồng bộ thường.
        if (previousDpd != projection.getDaysPastDue()) {
            outboxService.recordForPublication("FinoraLoan", loan.getId().toString(), "LoanDelinquencyChanged", 1,
                    new LoanDelinquencyChangedEventData(loan.getLoanApplicationId(), loan.getLoanNumber(),
                            loan.getBorrowerId(), loan.getFineractLoanId(), previousDpd, projection.getDaysPastDue(),
                            groups.previous(), groups.current(), projection.getOverdueAmount().toPlainString(),
                            projection.getOverdueSince(), projection.getTotalOutstanding().toPlainString(), now));
        }
        // P3 (D76): kỳ tiếp theo theo lịch mới lấy từ cùng snapshot Fineract với ngày đáo hạn mới; không tự tính lịch.
        outboxService.recordForPublication("FinoraLoan", loan.getId().toString(), "LoanRescheduled", 1,
                new LoanRescheduledEventData(loan.getLoanApplicationId(), loan.getLoanNumber(), loan.getBorrowerId(),
                        loan.getFineractLoanId(), request.getRequestId(), request.getRequestType(), previousMaturity,
                        snapshot.maturityDate(), request.getRescheduleFromDate(), request.getAdjustedDueDate(),
                        request.getExtraTerms(), now, snapshot.nextDueDate(), decimal(snapshot.nextDueAmount())));
    }

    /** Chuỗi decimal hai chữ số thập phân như projection servicing; {@code null} khi snapshot không có. */
    private static String decimal(java.math.BigDecimal value) {
        return value == null ? null : value.setScale(2, java.math.RoundingMode.HALF_UP).toPlainString();
    }

    private void fail(java.util.UUID requestId, String code, boolean retryable) {
        LoanRescheduleRequest request = loanRescheduleRequestRepository.findByRequestIdForUpdate(requestId)
                .orElseThrow();
        if (request.getStatus() != LoanRescheduleStatus.CREATING
                && request.getStatus() != LoanRescheduleStatus.APPROVING) return;
        Instant now = clock.instant();
        Duration backoff = backoff(request.getAttemptCount());
        request.fail(code, retryable, loanRescheduleProperties.maxAttempts(), now.plus(backoff), now);
        if (request.getStatus() == LoanRescheduleStatus.MANUAL_REVIEW) {
            FinoraLoan loan = finoraLoanRepository.findByIdForUpdate(request.getFinoraLoanId()).orElseThrow();
            var previous = request.getPreviousLoanStatus() == null
                    ? FinoraLoanStatus.ACTIVE : request.getPreviousLoanStatus();
            loan.restoreAfterFailedRestructuring(previous, now);
        }
    }

    private Duration backoff(int attempt) {
        long multiplier = 1L << Math.min(Math.max(0, attempt - 1), 20);
        try {
            Duration value = loanRescheduleProperties.retryDelay().multipliedBy(multiplier);
            return value.compareTo(loanRescheduleProperties.maximumRetryDelay()) > 0
                    ? loanRescheduleProperties.maximumRetryDelay() : value;
        } catch (ArithmeticException exception) {
            return loanRescheduleProperties.maximumRetryDelay();
        }
    }

    private static String marker(java.util.UUID requestId) {
        return "[FINORA:" + requestId + "]";
    }

    private record Work(java.util.UUID requestId, Long loanId, Long loanApplicationId, String loanNumber,
            String borrowerId, Long fineractLoanId, LoanRescheduleStep step, LoanRescheduleType requestType,
            LocalDate rescheduleFromDate, LocalDate adjustedDueDate, Integer extraTerms, Long coreRescheduleId) {}
}
