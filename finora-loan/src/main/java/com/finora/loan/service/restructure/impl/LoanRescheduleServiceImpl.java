package com.finora.loan.service.restructure.impl;

import com.finora.common.security.SecurityUtils;
import com.finora.loan.config.LoanRescheduleProperties;
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
import com.finora.loan.dto.restructure.response.LoanRescheduleResponse;
import com.finora.loan.exception.LoanBusinessException;
import com.finora.loan.messaging.event.LoanRescheduleConsentRequestedEventData;
import com.finora.loan.repository.restructure.LoanRescheduleRequestRepository;
import com.finora.loan.repository.servicing.FinoraLoanRepository;
import com.finora.loan.repository.servicing.LoanServicingProjectionRepository;
import com.finora.loan.service.outbox.OutboxService;
import com.finora.loan.service.restructure.LoanRescheduleService;
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
    private final LoanRescheduleProperties loanRescheduleProperties;
    private final HashingService hashingService;
    private final OutboxService outboxService;
    private final Clock clock;

    @Override
    @Transactional(readOnly = true)
    public LoanReschedulePolicyResponse policy() {
        return new LoanReschedulePolicyResponse(loanRescheduleProperties.termsVersion(),
                loanRescheduleProperties.termsText(), termsHash());
    }

    @Override
    @Transactional
    public LoanRescheduleResponse submit(String loanNumber, String idempotencyKey,
            CreateLoanRescheduleRequest input) {
        String borrowerId = SecurityUtils.getCurrentUserId();
        String requestHash = requestHash(loanNumber, borrowerId, input);
        LoanRescheduleRequest duplicate = loanRescheduleRequestRepository
                .findByBorrowerIdAndIdempotencyKey(borrowerId, idempotencyKey).orElse(null);
        if (duplicate != null) {
            if (!duplicate.getRequestHash().equals(requestHash)) {
                throw LoanBusinessException.conflict("IDEMPOTENCY_KEY_REUSED",
                        "Idempotency-Key đã được dùng cho nội dung cơ cấu khác");
            }
            return toResponse(duplicate);
        }
        validateInput(input);
        if (!loanRescheduleProperties.termsVersion().equals(input.confirmedTermsVersion())) {
            throw LoanBusinessException.conflict("RESTRUCTURE_TERMS_CHANGED",
                    "Điều khoản cơ cấu đã thay đổi, vui lòng đọc và xác nhận lại");
        }
        FinoraLoan loan = finoraLoanRepository.findByLoanNumberForUpdate(loanNumber).orElseThrow(() -> loanNotFound());
        requireOwner(loan, borrowerId);
        if (loan.getStatus() != FinoraLoanStatus.ACTIVE && loan.getStatus() != FinoraLoanStatus.DEFAULTED) {
            throw LoanBusinessException.conflict("LOAN_NOT_ACTIVE",
                    "Chỉ khoản vay đang hoạt động hoặc default mới được cơ cấu");
        }
        LoanServicingProjection projection = loanServicingProjectionRepository.findByFinoraLoanId(loan.getId())
                .orElseThrow(() -> new IllegalStateException("Khoản vay thiếu servicing projection"));
        LoanRescheduleRequest request = LoanRescheduleRequest.submit(
                UUID.randomUUID(), loan.getId(), loan.getLoanNumber(), borrowerId, input.requestType(),
                input.rescheduleFromDate(), input.adjustedDueDate(), input.extraTerms(), input.reasonComment(),
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

    private LoanRescheduleRequest findRequestForUpdate(UUID requestId) {
        return loanRescheduleRequestRepository.findByRequestIdForUpdate(requestId).orElseThrow(() ->
                new LoanBusinessException(HttpStatus.NOT_FOUND, "RESTRUCTURE_REQUEST_NOT_FOUND",
                        "Không tìm thấy yêu cầu cơ cấu"));
    }

    private void validateInput(CreateLoanRescheduleRequest input) {
        LocalDate today = LocalDate.now(clock);
        if (input.rescheduleFromDate().isBefore(today)) {
            throw LoanBusinessException.badRequest("RESCHEDULE_DATE_IN_PAST",
                    "Ngày bắt đầu cơ cấu không được ở quá khứ");
        }
        if (input.requestType() == LoanRescheduleType.INSTALLMENT_ADJUSTMENT) {
            if (input.adjustedDueDate() == null || input.extraTerms() != null
                    || !input.adjustedDueDate().isAfter(input.rescheduleFromDate())) {
                throw LoanBusinessException.badRequest("INVALID_INSTALLMENT_ADJUSTMENT",
                        "Điều chỉnh kỳ hạn cần ngày đến hạn mới sau ngày bắt đầu và không nhận số kỳ gia hạn");
            }
        } else if (input.adjustedDueDate() != null || input.extraTerms() == null || input.extraTerms() <= 0) {
            throw LoanBusinessException.badRequest("INVALID_TERM_EXTENSION",
                    "Gia hạn cần số kỳ dương và không nhận ngày đến hạn điều chỉnh");
        }
    }

    private String termsHash() {
        return hashingService.sha256(Map.of(
                "version", loanRescheduleProperties.termsVersion(), "text", loanRescheduleProperties.termsText()));
    }

    private String requestHash(String loanNumber, String borrowerId, CreateLoanRescheduleRequest input) {
        return hashingService.sha256(Map.of(
                "loanNumber", loanNumber,
                "borrowerId", borrowerId,
                "requestType", input.requestType(),
                "rescheduleFromDate", input.rescheduleFromDate(),
                "adjustedDueDate", input.adjustedDueDate() == null ? "" : input.adjustedDueDate(),
                "extraTerms", input.extraTerms() == null ? 0 : input.extraTerms(),
                "reasonComment", input.reasonComment(),
                "confirmedTermsVersion", input.confirmedTermsVersion()));
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
                request.getDecidedAt(), request.getCreatedAt(), request.getUpdatedAt());
    }
}
