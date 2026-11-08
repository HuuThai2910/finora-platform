package com.finora.loan.service.restructure.impl;

import com.finora.common.security.SecurityUtils;
import com.finora.loan.config.LoanRescheduleProperties;
import com.finora.loan.domain.contract.entity.LoanContract;
import com.finora.loan.domain.core.entity.ScheduleCalculationSnapshot;
import com.finora.loan.domain.restructure.entity.LoanRescheduleRequest;
import com.finora.loan.domain.restructure.enums.LoanRescheduleStatus;
import com.finora.loan.domain.restructure.enums.LoanRescheduleType;
import com.finora.loan.domain.servicing.entity.FinoraLoan;
import com.finora.loan.domain.servicing.entity.LoanServicingProjection;
import com.finora.loan.domain.servicing.enums.FinoraLoanStatus;
import com.finora.loan.dto.response.PageResponse;
import com.finora.loan.dto.restructure.request.AdminLoanRescheduleDecisionRequest;
import com.finora.loan.dto.restructure.request.CreateLoanRescheduleRequest;
import com.finora.loan.dto.restructure.response.LoanReschedulePolicyResponse;
import com.finora.loan.dto.restructure.response.LoanRescheduleEligibilityResponse;
import com.finora.loan.dto.restructure.response.LoanRescheduleResponse;
import com.finora.loan.exception.LoanBusinessException;
import com.finora.loan.messaging.event.LoanRescheduleConsentRequestedEventData;
import com.finora.loan.mapper.core.ScheduleCalculationSnapshotMapper;
import com.finora.loan.repository.contract.LoanContractRepository;
import com.finora.loan.repository.core.ScheduleCalculationSnapshotRepository;
import com.finora.loan.repository.restructure.LoanRescheduleRequestRepository;
import com.finora.loan.repository.servicing.FinoraLoanRepository;
import com.finora.loan.repository.servicing.LoanServicingProjectionRepository;
import com.finora.loan.repository.servicing.LoanServicingScheduleVersionRepository;
import com.finora.loan.repository.servicing.PendingScheduledRepaymentRepository;
import com.finora.loan.service.outbox.OutboxService;
import com.finora.loan.service.restructure.LoanRescheduleService;
import com.finora.loan.service.restructure.LoanTermExtensionLimitPolicy;
import com.finora.loan.util.HashingService;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Yêu cầu cơ cấu khoản vay: người vay đọc điều khoản, gửi và xem yêu cầu của mình; quản trị duyệt (chuyển sang chờ nhà
 * đầu tư đồng ý) hoặc từ chối.
 * <p>
 * Gửi yêu cầu idempotent theo {@code Idempotency-Key} kèm hash nội dung; duyệt ghi event
 * {@code LoanRescheduleConsentRequested} qua outbox trong cùng transaction.
 */
@Service
@RequiredArgsConstructor
public class LoanRescheduleServiceImpl implements LoanRescheduleService {
    private final LoanRescheduleRequestRepository loanRescheduleRequestRepository;
    private final FinoraLoanRepository finoraLoanRepository;
    private final LoanServicingProjectionRepository loanServicingProjectionRepository;
    private final LoanContractRepository loanContractRepository;
    private final ScheduleCalculationSnapshotRepository scheduleCalculationSnapshotRepository;
    private final ScheduleCalculationSnapshotMapper scheduleCalculationSnapshotMapper;
    private final LoanServicingScheduleVersionRepository loanServicingScheduleVersionRepository;
    private final PendingScheduledRepaymentRepository pendingScheduledRepaymentRepository;
    private final LoanRescheduleProperties loanRescheduleProperties;
    private final HashingService hashingService;
    private final OutboxService outboxService;
    private final Clock clock;

    @Override
    @Transactional(readOnly = true)
    public LoanReschedulePolicyResponse policy() {
        return new LoanReschedulePolicyResponse(loanRescheduleProperties.termsVersion(),
                loanRescheduleProperties.termsText(), termsHash(),
                loanRescheduleProperties.maximumExtraTermsPerRequest(),
                loanRescheduleProperties.maximumCumulativeExtraTerms(),
                loanRescheduleProperties.maximumTotalTerms());
    }

    @Override
    @Transactional(readOnly = true)
    public LoanRescheduleEligibilityResponse eligibility(String loanNumber) {
        String borrowerId = SecurityUtils.getCurrentUserId();
        FinoraLoan loan = finoraLoanRepository.findByLoanNumber(loanNumber).orElseThrow(() -> loanNotFound());
        requireOwner(loan, borrowerId);
        ExtensionLimit limit = extensionLimit(loan);
        String unavailableReason = limit.maximumExtraTerms() > 0 ? null
                : "Hợp đồng đã đạt thời hạn tối đa " + loanRescheduleProperties.maximumTotalTerms()
                        + " tháng nên không thể gia hạn thêm kỳ.";
        return new LoanRescheduleEligibilityResponse(
                loanRescheduleProperties.termsVersion(), loanRescheduleProperties.termsText(), termsHash(),
                loanRescheduleProperties.maximumTotalTerms(), limit.currentTotalTerms(),
                limit.maximumExtraTerms(), limit.maximumExtraTerms() > 0, unavailableReason);
    }

    @Override
    @Transactional
    public LoanRescheduleResponse submit(String loanNumber, String idempotencyKey,
            CreateLoanRescheduleRequest input) {
        String borrowerId = SecurityUtils.getCurrentUserId();
        validateInput(input);
        if (!loanRescheduleProperties.termsVersion().equals(input.confirmedTermsVersion())) {
            throw LoanBusinessException.conflict("RESTRUCTURE_TERMS_CHANGED",
                    "Điều khoản cơ cấu đã thay đổi, vui lòng đọc và xác nhận lại");
        }
        LoanRescheduleRequest duplicate = loanRescheduleRequestRepository
                .findByBorrowerIdAndIdempotencyKey(borrowerId, idempotencyKey).orElse(null);
        if (duplicate != null) {
            String duplicateHash = requestHash(loanNumber, borrowerId, input, duplicate.getRescheduleFromDate());
            if (!duplicate.getRequestHash().equals(duplicateHash)) {
                throw LoanBusinessException.conflict("IDEMPOTENCY_KEY_REUSED",
                        "Idempotency-Key đã được dùng cho nội dung cơ cấu khác");
            }
            return toResponse(duplicate);
        }
        FinoraLoan loan = finoraLoanRepository.findByLoanNumberForUpdate(loanNumber).orElseThrow(() -> loanNotFound());
        requireOwner(loan, borrowerId);
        if (loan.getStatus() != FinoraLoanStatus.ACTIVE && loan.getStatus() != FinoraLoanStatus.DEFAULTED) {
            throw LoanBusinessException.conflict("LOAN_NOT_ACTIVE",
                    "Chỉ khoản vay đang hoạt động hoặc default mới được cơ cấu");
        }
        LoanServicingProjection projection = loanServicingProjectionRepository.findByFinoraLoanId(loan.getId())
                .orElseThrow(() -> new IllegalStateException("Khoản vay thiếu servicing projection"));
        LocalDate effectiveFromDate = effectiveFromDate(input, loan, projection);
        validateExtensionLimits(loan, input);
        String requestHash = requestHash(loanNumber, borrowerId, input, effectiveFromDate);
        LoanRescheduleRequest request = LoanRescheduleRequest.submit(
                UUID.randomUUID(), loan.getId(), loan.getLoanNumber(), borrowerId, input.requestType(),
                effectiveFromDate, input.adjustedDueDate(), input.extraTerms(), input.reasonComment(),
                loanRescheduleProperties.termsVersion(), termsHash(), idempotencyKey, requestHash,
                projection.getMaturityDate(), clock.instant());
        try {
            return toResponse(loanRescheduleRequestRepository.saveAndFlush(request));
        } catch (DataIntegrityViolationException exception) {
            throw LoanBusinessException.conflict("ACTIVE_RESTRUCTURE_REQUEST_EXISTS",
                    "Khoản vay đã có một yêu cầu cơ cấu chưa kết thúc");
        }
    }

    @Override
    @Transactional(readOnly = true)
    public PageResponse<LoanRescheduleResponse> listMine(String loanNumber, int page, int size) {
        String borrowerId = SecurityUtils.getCurrentUserId();
        FinoraLoan loan = finoraLoanRepository.findByLoanNumber(loanNumber).orElseThrow(() -> loanNotFound());
        requireOwner(loan, borrowerId);
        Page<LoanRescheduleResponse> result = loanRescheduleRequestRepository
                .findByBorrowerIdAndLoanNumberOrderByCreatedAtDesc(
                        borrowerId, loanNumber, PageRequest.of(page, size))
                .map(this::toResponse);
        return PageResponse.from(result);
    }

    @Override
    @Transactional(readOnly = true)
    public PageResponse<LoanRescheduleResponse> listAdmin(LoanRescheduleStatus status, int page, int size) {
        Page<LoanRescheduleRequest> requests = status == null
                ? loanRescheduleRequestRepository.findAllByOrderByCreatedAtDesc(PageRequest.of(page, size))
                : loanRescheduleRequestRepository.findByStatusOrderByCreatedAtDesc(status, PageRequest.of(page, size));
        return PageResponse.from(requests.map(this::toResponse));
    }

    @Override
    @Transactional
    public LoanRescheduleResponse approve(UUID requestId, String decisionKey,
            AdminLoanRescheduleDecisionRequest input) {
        LoanRescheduleRequest request = findRequestForUpdate(requestId);
        if (request.sameDecision(decisionKey)) return toResponse(request);
        FinoraLoan loan = finoraLoanRepository.findByIdForUpdate(request.getFinoraLoanId()).orElseThrow();
        Instant now = clock.instant();
        request.approveForInvestorConsent(SecurityUtils.getCurrentUserId(), input.comment(), decisionKey, now);
        outboxService.recordForPublication("LoanRescheduleRequest", requestId.toString(),
                "LoanRescheduleConsentRequested", 1,
                new LoanRescheduleConsentRequestedEventData(
                        request.getRequestId(), loan.getLoanApplicationId(), loan.getLoanNumber(),
                        request.getRequestType().name(), request.getRescheduleFromDate(),
                        request.getAdjustedDueDate(), request.getExtraTerms(),
                        request.getOriginalMaturityDate(), now, loan.getBorrowerId()));
        return toResponse(request);
    }

    @Override
    @Transactional
    public LoanRescheduleResponse reject(UUID requestId, String decisionKey,
            AdminLoanRescheduleDecisionRequest input) {
        LoanRescheduleRequest request = findRequestForUpdate(requestId);
        if (request.sameDecision(decisionKey)) return toResponse(request);
        if (input.comment() == null || input.comment().isBlank()) {
            throw LoanBusinessException.badRequest("REJECTION_REASON_REQUIRED", "Từ chối cơ cấu phải có lý do");
        }
        request.reject(SecurityUtils.getCurrentUserId(), input.comment(), decisionKey, clock.instant());
        return toResponse(request);
    }

    @Override
    @Transactional
    public LoanRescheduleResponse retryManual(UUID requestId) {
        LoanRescheduleRequest request = findRequestForUpdate(requestId);
        FinoraLoan loan = finoraLoanRepository.findByIdForUpdate(request.getFinoraLoanId()).orElseThrow();
        Instant now = clock.instant();
        if (loan.getStatus() != FinoraLoanStatus.RESTRUCTURING) loan.beginRestructuring(now);
        request.retryManual(now);
        return toResponse(request);
    }

    @Override
    @Transactional
    public LoanRescheduleResponse cancelManual(UUID requestId, String decisionKey,
            AdminLoanRescheduleDecisionRequest input) {
        LoanRescheduleRequest request = findRequestForUpdate(requestId);
        if (request.sameDecision(decisionKey) && request.getStatus() == LoanRescheduleStatus.CANCELLED) {
            return toResponse(request);
        }
        if (input.comment() == null || input.comment().isBlank()) {
            throw LoanBusinessException.badRequest("CANCELLATION_REASON_REQUIRED",
                    "Đóng yêu cầu cơ cấu lỗi phải có lý do");
        }
        Instant now = clock.instant();
        FinoraLoan loan = finoraLoanRepository.findByIdForUpdate(request.getFinoraLoanId()).orElseThrow();
        request.cancelManual(SecurityUtils.getCurrentUserId(), input.comment(), decisionKey, now);
        loan.restoreAfterFailedRestructuring(request.getPreviousLoanStatus() == null
                ? FinoraLoanStatus.ACTIVE : request.getPreviousLoanStatus(), now);
        return toResponse(request);
    }

    private LoanRescheduleRequest findRequestForUpdate(UUID requestId) {
        return loanRescheduleRequestRepository.findByRequestIdForUpdate(requestId).orElseThrow(() ->
                new LoanBusinessException(HttpStatus.NOT_FOUND, "RESTRUCTURE_REQUEST_NOT_FOUND",
                        "Không tìm thấy yêu cầu cơ cấu"));
    }

    private void validateInput(CreateLoanRescheduleRequest input) {
        LocalDate today = LocalDate.now(clock);
        if (input.requestType() == LoanRescheduleType.INSTALLMENT_ADJUSTMENT) {
            if (input.rescheduleFromDate() == null || input.adjustedDueDate() == null || input.extraTerms() != null
                    || !input.adjustedDueDate().isAfter(input.rescheduleFromDate())) {
                throw LoanBusinessException.badRequest("INVALID_INSTALLMENT_ADJUSTMENT",
                        "Điều chỉnh kỳ hạn cần ngày đến hạn mới sau ngày bắt đầu và không nhận số kỳ gia hạn");
            }
            if (input.adjustedDueDate().isBefore(today)) {
                throw LoanBusinessException.badRequest("ADJUSTED_DUE_DATE_IN_PAST",
                        "Ngày đến hạn mới không được ở quá khứ");
            }
        } else if (input.adjustedDueDate() != null || input.extraTerms() == null || input.extraTerms() <= 0) {
            throw LoanBusinessException.badRequest("INVALID_TERM_EXTENSION",
                    "Gia hạn cần số kỳ dương và không nhận ngày đến hạn điều chỉnh");
        }
    }

    /** Gia hạn toàn khoản luôn bắt đầu ở kỳ chưa hoàn thành gần nhất; client không tự chọn mốc core. */
    private LocalDate effectiveFromDate(CreateLoanRescheduleRequest input, FinoraLoan loan,
            LoanServicingProjection projection) {
        if (input.requestType() == LoanRescheduleType.INSTALLMENT_ADJUSTMENT) {
            return input.rescheduleFromDate();
        }
        if (projection.getNextDueDate() == null) {
            throw LoanBusinessException.conflict("NO_OUTSTANDING_INSTALLMENT",
                    "Khoản vay không còn kỳ chưa thanh toán để gia hạn");
        }
        LocalDate nextDueDate = projection.getNextDueDate();
        var pending = pendingScheduledRepaymentRepository.findByLoanApplicationId(loan.getLoanApplicationId())
                .filter(value -> value.getBorrowerId().equals(loan.getBorrowerId()))
                .filter(value -> value.getDueDate().equals(nextDueDate));
        if (pending.isEmpty()) return nextDueDate;

        return effectivePeriods(loan).stream()
                .map(com.finora.loan.dto.core.response.SchedulePeriodResponse::dueDate)
                .filter(date -> date != null && date.isAfter(pending.orElseThrow().getDueDate()))
                .min(LocalDate::compareTo)
                .orElseThrow(() -> LoanBusinessException.conflict("NO_INSTALLMENT_AFTER_PENDING_PAYMENT",
                        "Kỳ cuối đã được thanh toán trước và khoản vay không còn kỳ tiếp theo để gia hạn"));
    }

    private java.util.List<com.finora.loan.dto.core.response.SchedulePeriodResponse> effectivePeriods(FinoraLoan loan) {
        var latest = loanServicingScheduleVersionRepository
                .findFirstByFinoraLoanIdOrderByScheduleVersionDesc(loan.getId());
        if (latest.isPresent()) {
            return scheduleCalculationSnapshotMapper.periods(latest.orElseThrow().getPeriodsSnapshotJson());
        }
        LoanContract contract = loanContractRepository.findByContractNumber(loan.getContractNumber())
                .orElseThrow(() -> new IllegalStateException("Khoản vay thiếu hợp đồng nguồn"));
        ScheduleCalculationSnapshot snapshot = scheduleCalculationSnapshotRepository
                .findById(contract.getCalculationSnapshotId())
                .orElseThrow(() -> new IllegalStateException("Khoản vay thiếu snapshot lịch trả"));
        return scheduleCalculationSnapshotMapper.periods(snapshot);
    }

    private void validateExtensionLimits(FinoraLoan loan, CreateLoanRescheduleRequest input) {
        if (input.requestType() != LoanRescheduleType.TERM_EXTENSION) return;
        int requested = input.extraTerms();
        if (requested > loanRescheduleProperties.maximumExtraTermsPerRequest()) {
            throw LoanBusinessException.badRequest("EXTRA_TERMS_LIMIT_EXCEEDED",
                    "Mỗi lần chỉ được gia hạn tối đa "
                            + loanRescheduleProperties.maximumExtraTermsPerRequest() + " kỳ");
        }
        ExtensionLimit limit = extensionLimit(loan);
        long completedExtraTerms = limit.completedExtraTerms();
        if (completedExtraTerms + requested > loanRescheduleProperties.maximumCumulativeExtraTerms()) {
            throw LoanBusinessException.conflict("CUMULATIVE_EXTRA_TERMS_LIMIT_EXCEEDED",
                    "Khoản vay chỉ được gia hạn cộng dồn tối đa "
                            + loanRescheduleProperties.maximumCumulativeExtraTerms() + " kỳ");
        }
        if (limit.currentTotalTerms() + requested > loanRescheduleProperties.maximumTotalTerms()) {
            throw LoanBusinessException.conflict("TOTAL_TERMS_LIMIT_EXCEEDED",
                    "Tổng thời hạn sau cơ cấu không được vượt quá "
                            + loanRescheduleProperties.maximumTotalTerms() + " kỳ");
        }
    }

    private ExtensionLimit extensionLimit(FinoraLoan loan) {
        long completedExtraTerms = loanRescheduleRequestRepository.findByFinoraLoanIdOrderByCreatedAtAsc(loan.getId())
                .stream()
                .filter(request -> request.getRequestType() == LoanRescheduleType.TERM_EXTENSION
                        && request.getStatus() == LoanRescheduleStatus.COMPLETED)
                .map(LoanRescheduleRequest::getExtraTerms)
                .mapToLong(Integer::longValue)
                .sum();
        LoanContract contract = loanContractRepository.findByContractNumber(loan.getContractNumber())
                .orElseThrow(() -> new IllegalStateException("Khoản vay thiếu hợp đồng nguồn"));
        ScheduleCalculationSnapshot snapshot = scheduleCalculationSnapshotRepository
                .findById(contract.getCalculationSnapshotId())
                .orElseThrow(() -> new IllegalStateException("Khoản vay thiếu snapshot lịch trả"));
        int originalTerms = scheduleCalculationSnapshotMapper.periods(snapshot).size();
        int completed = Math.toIntExact(completedExtraTerms);
        LoanTermExtensionLimitPolicy.Result result = LoanTermExtensionLimitPolicy.evaluate(
                originalTerms, completed,
                loanRescheduleProperties.maximumExtraTermsPerRequest(),
                loanRescheduleProperties.maximumCumulativeExtraTerms(),
                loanRescheduleProperties.maximumTotalTerms());
        return new ExtensionLimit(completed, result.currentTotalTerms(), result.maximumExtraTerms());
    }

    private record ExtensionLimit(int completedExtraTerms, int currentTotalTerms, int maximumExtraTerms) {}

    private String termsHash() {
        return hashingService.sha256(Map.of(
                "version", loanRescheduleProperties.termsVersion(), "text", loanRescheduleProperties.termsText()));
    }

    private String requestHash(String loanNumber, String borrowerId, CreateLoanRescheduleRequest input,
            LocalDate effectiveFromDate) {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("loanNumber", loanNumber);
        values.put("borrowerId", borrowerId);
        values.put("requestType", input.requestType());
        values.put("rescheduleFromDate", effectiveFromDate);
        values.put("adjustedDueDate", input.adjustedDueDate() == null ? "" : input.adjustedDueDate());
        values.put("extraTerms", input.extraTerms() == null ? 0 : input.extraTerms());
        values.put("reasonComment", input.reasonComment());
        values.put("confirmedTermsVersion", input.confirmedTermsVersion());
        return hashingService.sha256(values);
    }

    private static void requireOwner(FinoraLoan loan, String borrowerId) {
        if (!loan.getBorrowerId().equals(borrowerId)) {
            throw LoanBusinessException.forbidden("LOAN_ACCESS_DENIED", "Khoản vay không thuộc người dùng hiện tại");
        }
    }

    private static LoanBusinessException loanNotFound() {
        return new LoanBusinessException(HttpStatus.NOT_FOUND, "LOAN_NOT_FOUND", "Không tìm thấy khoản vay");
    }

    private LoanRescheduleResponse toResponse(LoanRescheduleRequest request) {
        return new LoanRescheduleResponse(request.getRequestId(), request.getLoanNumber(), request.getRequestType(),
                request.getRescheduleFromDate(), request.getAdjustedDueDate(), request.getExtraTerms(),
                request.getReasonComment(), request.getTermsVersion(), request.getStatus(),
                request.getOriginalMaturityDate(), request.getNewMaturityDate(), request.getDecisionComment(),
                request.getLastErrorCode(), request.getDecidedAt(), request.getCreatedAt(), request.getUpdatedAt());
    }
}
