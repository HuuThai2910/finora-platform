package com.finora.loan.service.restructure;

import com.finora.common.security.SecurityUtils;
import com.finora.loan.config.LoanRescheduleProperties;
import com.finora.loan.domain.restructure.LoanRescheduleRequest;
import com.finora.loan.domain.restructure.LoanRescheduleStatus;
import com.finora.loan.domain.restructure.LoanRescheduleType;
import com.finora.loan.domain.servicing.FinoraLoan;
import com.finora.loan.domain.servicing.FinoraLoanStatus;
import com.finora.loan.domain.servicing.LoanServicingProjection;
import com.finora.loan.dto.common.PageResponse;
import com.finora.loan.dto.restructure.request.AdminLoanRescheduleDecisionRequest;
import com.finora.loan.dto.restructure.request.CreateLoanRescheduleRequest;
import com.finora.loan.dto.restructure.response.LoanReschedulePolicyResponse;
import com.finora.loan.dto.restructure.response.LoanRescheduleResponse;
import com.finora.loan.exception.LoanBusinessException;
import com.finora.loan.repository.restructure.LoanRescheduleRequestRepository;
import com.finora.loan.repository.servicing.FinoraLoanRepository;
import com.finora.loan.repository.servicing.LoanServicingProjectionRepository;
import com.finora.loan.support.HashingService;
import java.time.Clock;
import java.time.LocalDate;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class LoanRescheduleService {
    private final LoanRescheduleRequestRepository requests;
    private final FinoraLoanRepository loans;
    private final LoanServicingProjectionRepository projections;
    private final LoanRescheduleProperties properties;
    private final HashingService hashing;
    private final Clock clock;

    @Transactional(readOnly = true)
    public LoanReschedulePolicyResponse policy() {
        return new LoanReschedulePolicyResponse(properties.termsVersion(), properties.termsText(), termsHash());
    }

    @Transactional
    public LoanRescheduleResponse submit(String loanNumber, String idempotencyKey,
            CreateLoanRescheduleRequest input) {
        String borrowerId = SecurityUtils.getCurrentUserId();
        String requestHash = requestHash(loanNumber, borrowerId, input);
        LoanRescheduleRequest duplicate = requests
                .findByBorrowerIdAndIdempotencyKey(borrowerId, idempotencyKey).orElse(null);
        if (duplicate != null) {
            if (!duplicate.getRequestHash().equals(requestHash)) {
                throw LoanBusinessException.conflict("IDEMPOTENCY_KEY_REUSED",
                        "Idempotency-Key đã được dùng cho nội dung cơ cấu khác");
            }
            return response(duplicate);
        }
        validateInput(input);
        if (!properties.termsVersion().equals(input.confirmedTermsVersion())) {
            throw LoanBusinessException.conflict("RESTRUCTURE_TERMS_CHANGED",
                    "Điều khoản cơ cấu đã thay đổi, vui lòng đọc và xác nhận lại");
        }
        FinoraLoan loan = loans.findByLoanNumberForUpdate(loanNumber).orElseThrow(() -> notFound());
        requireOwner(loan, borrowerId);
        if (loan.getStatus() != FinoraLoanStatus.ACTIVE && loan.getStatus() != FinoraLoanStatus.DEFAULTED) {
            throw LoanBusinessException.conflict("LOAN_NOT_ACTIVE",
                    "Chỉ khoản vay đang hoạt động hoặc default mới được cơ cấu");
        }
        LoanServicingProjection projection = projections.findByFinoraLoanId(loan.getId()).orElseThrow(() ->
                new IllegalStateException("Khoản vay thiếu servicing projection"));
        LoanRescheduleRequest request = LoanRescheduleRequest.submit(
                UUID.randomUUID(), loan.getId(), loan.getLoanNumber(), borrowerId, input.requestType(),
                input.rescheduleFromDate(), input.adjustedDueDate(), input.extraTerms(), input.reasonComment(),
                properties.termsVersion(), termsHash(), idempotencyKey, requestHash,
                projection.getMaturityDate(), clock.instant());
        try {
            return response(requests.saveAndFlush(request));
        } catch (DataIntegrityViolationException exception) {
            throw LoanBusinessException.conflict("ACTIVE_RESTRUCTURE_REQUEST_EXISTS",
                    "Khoản vay đã có một yêu cầu cơ cấu chưa kết thúc");
        }
    }

    @Transactional(readOnly = true)
    public PageResponse<LoanRescheduleResponse> listMine(String loanNumber, int page, int size) {
        String borrowerId = SecurityUtils.getCurrentUserId();
        FinoraLoan loan = loans.findByLoanNumber(loanNumber).orElseThrow(() -> notFound());
        requireOwner(loan, borrowerId);
        Page<LoanRescheduleResponse> result = requests
                .findByBorrowerIdAndLoanNumberOrderByCreatedAtDesc(
                        borrowerId, loanNumber, PageRequest.of(page, size))
                .map(this::response);
        return PageResponse.from(result);
    }

    @Transactional(readOnly = true)
    public PageResponse<LoanRescheduleResponse> listAdmin(LoanRescheduleStatus status, int page, int size) {
        Page<LoanRescheduleRequest> values = status == null
                ? requests.findAllByOrderByCreatedAtDesc(PageRequest.of(page, size))
                : requests.findByStatusOrderByCreatedAtDesc(status, PageRequest.of(page, size));
        return PageResponse.from(values.map(this::response));
    }

    @Transactional
    public LoanRescheduleResponse approve(UUID requestId, String decisionKey,
            AdminLoanRescheduleDecisionRequest input) {
        LoanRescheduleRequest request = locked(requestId);
        if (request.sameDecision(decisionKey)) return response(request);
        FinoraLoan loan = loans.findByIdForUpdate(request.getFinoraLoanId()).orElseThrow();
        loan.beginRestructuring(clock.instant());
        request.approve(SecurityUtils.getCurrentUserId(), input.comment(), decisionKey, clock.instant());
        return response(request);
    }

    @Transactional
    public LoanRescheduleResponse reject(UUID requestId, String decisionKey,
            AdminLoanRescheduleDecisionRequest input) {
        LoanRescheduleRequest request = locked(requestId);
        if (request.sameDecision(decisionKey)) return response(request);
        if (input.comment() == null || input.comment().isBlank()) {
            throw LoanBusinessException.badRequest("REJECTION_REASON_REQUIRED", "Từ chối cơ cấu phải có lý do");
        }
        request.reject(SecurityUtils.getCurrentUserId(), input.comment(), decisionKey, clock.instant());
        return response(request);
    }

    private LoanRescheduleRequest locked(UUID requestId) {
        return requests.findByRequestIdForUpdate(requestId).orElseThrow(() ->
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
        return hashing.sha256(Map.of("version", properties.termsVersion(), "text", properties.termsText()));
    }

    private String requestHash(String loanNumber, String borrowerId, CreateLoanRescheduleRequest input) {
        return hashing.sha256(Map.of(
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

    private static LoanBusinessException notFound() {
        return new LoanBusinessException(HttpStatus.NOT_FOUND, "LOAN_NOT_FOUND", "Không tìm thấy khoản vay");
    }

    private LoanRescheduleResponse response(LoanRescheduleRequest value) {
        return new LoanRescheduleResponse(value.getRequestId(), value.getLoanNumber(), value.getRequestType(),
                value.getRescheduleFromDate(), value.getAdjustedDueDate(), value.getExtraTerms(),
                value.getReasonComment(), value.getTermsVersion(), value.getStatus(),
                value.getOriginalMaturityDate(), value.getNewMaturityDate(), value.getDecisionComment(),
                value.getDecidedAt(), value.getCreatedAt(), value.getUpdatedAt());
    }
}
