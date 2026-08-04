package com.finora.loan.service.impl;

import com.finora.common.exception.ResourceNotFoundException;
import com.finora.loan.config.MockCurrentUserProvider;
import com.finora.loan.domain.ActorType;
import com.finora.loan.domain.LoanApplication;
import com.finora.loan.domain.LoanApplicationStatus;
import com.finora.loan.domain.LoanApplicationStatusHistory;
import com.finora.loan.domain.LoanPurpose;
import com.finora.loan.domain.ScheduleCalculationSnapshot;
import com.finora.loan.dto.request.CreateLoanApplicationRequest;
import com.finora.loan.dto.request.WithdrawLoanApplicationRequest;
import com.finora.loan.dto.response.LoanApplicationHistoryResponse;
import com.finora.loan.dto.response.LoanApplicationResponse;
import com.finora.loan.dto.response.LoanPurposeResponse;
import com.finora.loan.dto.response.PageResponse;
import com.finora.loan.exception.LoanBusinessException;
import com.finora.loan.integration.fineract.FineractIntegrationException;
import com.finora.loan.integration.fineract.FineractScheduleGateway;
import com.finora.loan.integration.fineract.ScheduleCalculationRequest;
import com.finora.loan.integration.fineract.ScheduleCalculationResult;
import com.finora.loan.mapper.LoanApplicationMapper;
import com.finora.loan.repository.LoanApplicationRepository;
import com.finora.loan.repository.LoanApplicationStatusHistoryRepository;
import com.finora.loan.service.ApplicationPersistResult;
import com.finora.loan.service.ApplicationSubmissionContext;
import com.finora.loan.service.HashingService;
import com.finora.loan.service.LoanApplicationService;
import com.finora.loan.service.LoanApplicationSubmissionStateService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class LoanApplicationServiceImpl implements LoanApplicationService {

    private final LoanApplicationRepository applicationRepository;
    private final LoanApplicationStatusHistoryRepository historyRepository;
    private final LoanApplicationSubmissionStateService submissionState;
    private final FineractScheduleGateway scheduleGateway;
    private final LoanApplicationMapper mapper;
    private final HashingService hashingService;
    private final MockCurrentUserProvider currentUser;
    private final Clock clock;

    @Value("${finora.loan.pricing-disclosure-version:RATE_DISCLOSURE_V1}")
    private String pricingDisclosureVersion;

    /**
     * Tạo thẳng hồ sơ SUBMITTED và lưu lịch trả Fineract làm bằng chứng tính toán.
     *
     * <p>Cuộc gọi mạng được thực hiện ngoài transaction database. Sau khi nhận kết quả,
     * service khóa lại Product và tái kiểm tra version/mapping trước khi ghi nguyên tử
     * Application, calculation snapshot và status history.</p>
     */
    @Override
    public LoanApplicationResponse submit(CreateLoanApplicationRequest request, String idempotencyKey) {
        String borrowerId = currentUser.borrowerUserId();
        validateDisclosure(request);
        String normalizedKey = idempotencyKey.trim();
        String requestHash = hashingService.sha256(request);

        // Kiểm tra idempotency trước khi gọi Fineract để request lặp không tính lịch và tạo hồ sơ lần hai.
        LoanApplication existing = submissionState.findExisting(borrowerId, normalizedKey).orElse(null);
        if (existing != null) {
            return existingResponse(existing, requestHash);
        }

        // Chụp Product/mapping trước khi ra mạng; bước persist sẽ khóa và kiểm tra lại để chống Product đổi giữa chừng.
        ApplicationSubmissionContext context = submissionState.prepareContext(request);
        String scheduleRequestId = UUID.randomUUID().toString();
        ScheduleCalculationResult schedule;
        try {
            // Không mở transaction database trong lúc chờ Fineract để tránh giữ connection/lock lâu.
            schedule = scheduleGateway.calculateSchedule(new ScheduleCalculationRequest(
                    context.productId(),
                    context.fineractProductId(),
                    request.requestedAmount(),
                    request.requestedTermMonths(),
                    context.annualInterestRate(),
                    context.repaymentMethod(),
                    LocalDate.now(clock),
                    request.expectedDisbursementDate()
            ));
        } catch (FineractIntegrationException exception) {
            log.warn("Không thể tính lịch trả khi nộp hồ sơ: productId={}, code={}, retryable={}",
                    request.loanProductId(), exception.getCode(), exception.isRetryable());
            throw LoanBusinessException.serviceUnavailable(
                    "FINERACT_SCHEDULE_UNAVAILABLE",
                    "Chưa thể tính lịch trả. Vui lòng thử lại sau"
            );
        }

        try {
            // Một transaction mới ghi Application + financial/Product/schedule snapshot + history nguyên tử.
            ApplicationPersistResult result = submissionState.persist(
                    borrowerId,
                    normalizedKey,
                    requestHash,
                    scheduleRequestId,
                    pricingDisclosureVersion,
                    request,
                    context,
                    schedule
            );
            log.info("Đã nộp hồ sơ vay: applicationNumber={}, productId={}, borrowerId={}",
                    result.application().getApplicationNumber(), request.loanProductId(), borrowerId);
            return mapper.toResponse(result.application(), result.calculationSnapshot());
        } catch (DataIntegrityViolationException exception) {
            // Hai request cùng key có thể cùng vượt qua lần kiểm tra đầu; unique key là chốt chặn cuối.
            LoanApplication raced = submissionState.findExisting(borrowerId, normalizedKey)
                    .orElseThrow(() -> exception);
            return existingResponse(raced, requestHash);
        }
    }

    /** Rút hồ sơ đang SUBMITTED; thay đổi trạng thái và history cùng transaction. */
    @Override
    @Transactional
    public LoanApplicationResponse withdraw(String applicationNumber, WithdrawLoanApplicationRequest request) {
        LoanApplication application = getApplication(applicationNumber);
        Instant now = Instant.now(clock);
        application.withdraw(request.version(), request.reason(), currentUser.borrowerUserId(), now);
        applicationRepository.saveAndFlush(application);
        historyRepository.save(LoanApplicationStatusHistory.create(
                application.getId(), LoanApplicationStatus.SUBMITTED, LoanApplicationStatus.WITHDRAWN,
                "APPLICATION_WITHDRAWN", request.reason(), ActorType.BORROWER,
                currentUser.borrowerUserId(), now));
        return mapper.toResponse(application, submissionState.getCalculation(application.getId()));
    }

    @Override
    @Transactional(readOnly = true)
    public LoanApplicationResponse getMine(String applicationNumber) {
        LoanApplication application = getApplication(applicationNumber);
        application.requireOwner(currentUser.borrowerUserId());
        return mapper.toResponse(application, submissionState.getCalculation(application.getId()));
    }

    /**
     * Danh sách không tải calculation snapshot cho từng dòng, nhờ đó không phát sinh N+1.
     * Chi tiết lịch tính được trả ở endpoint chi tiết một hồ sơ.
     */
    @Override
    @Transactional(readOnly = true)
    public PageResponse<LoanApplicationResponse> listMine(int page, int size) {
        Page<LoanApplicationResponse> result = applicationRepository.findByBorrowerId(
                        currentUser.borrowerUserId(),
                        PageRequest.of(page, size, Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("id"))))
                .map(application -> mapper.toResponse(application, null));
        return PageResponse.from(result);
    }

    @Override
    @Transactional(readOnly = true)
    public PageResponse<LoanApplicationHistoryResponse> history(String applicationNumber, int page, int size) {
        LoanApplication application = getApplication(applicationNumber);
        application.requireOwner(currentUser.borrowerUserId());
        Page<LoanApplicationHistoryResponse> result = historyRepository.findByLoanApplicationId(
                        application.getId(),
                        PageRequest.of(page, size, Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("id"))))
                .map(this::toHistoryResponse);
        return PageResponse.from(result);
    }

    @Override
    @Transactional(readOnly = true)
    public List<LoanPurposeResponse> purposes() {
        return Arrays.stream(LoanPurpose.values())
                .map(purpose -> new LoanPurposeResponse(
                        purpose.name(), purpose.getLabel(), purpose.getAiValue(), purpose.isRequiresDetail()))
                .toList();
    }

    private LoanApplicationResponse existingResponse(LoanApplication existing, String requestHash) {
        // Cùng key chỉ được trả lại hồ sơ cũ khi nội dung request hoàn toàn giống nhau.
        if (!existing.getRequestHash().equals(requestHash)) {
            throw LoanBusinessException.conflict(
                    "IDEMPOTENCY_KEY_REUSED",
                    "Idempotency-Key đã được dùng cho nội dung hồ sơ khác"
            );
        }
        return mapper.toResponse(existing, submissionState.getCalculation(existing.getId()));
    }

    private void validateDisclosure(CreateLoanApplicationRequest request) {
        // Backend xác minh đúng phiên bản nội dung borrower đã xem, không chỉ tin một checkbox boolean.
        if (!Boolean.TRUE.equals(request.pricingDisclosureAccepted())) {
            throw LoanBusinessException.badRequest(
                    "PRICING_DISCLOSURE_NOT_ACCEPTED",
                    "Phải xác nhận đã xem lãi suất và lịch trả dự kiến"
            );
        }
        if (!pricingDisclosureVersion.equals(request.pricingDisclosureVersion())) {
            throw LoanBusinessException.conflict(
                    "PRICING_DISCLOSURE_OUTDATED",
                    "Nội dung công bố lãi suất đã thay đổi, vui lòng xem lại"
            );
        }
    }

    private LoanApplication getApplication(String applicationNumber) {
        return applicationRepository.findByApplicationNumber(applicationNumber)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Loan Application", "applicationNumber", applicationNumber));
    }

    private LoanApplicationHistoryResponse toHistoryResponse(LoanApplicationStatusHistory history) {
        return new LoanApplicationHistoryResponse(
                history.getId(), history.getFromStatus(), history.getToStatus(), history.getReasonCode(),
                history.getReasonDetail(), history.getActorType(), history.getActorId(), history.getCreatedAt());
    }
}
