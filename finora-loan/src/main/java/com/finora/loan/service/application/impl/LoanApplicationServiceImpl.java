package com.finora.loan.service.application.impl;

import com.finora.common.exception.ResourceNotFoundException;
import com.finora.common.security.SecurityUtils;
import com.finora.loan.config.LoanPricingDisclosureProperties;
import com.finora.loan.domain.application.entity.BorrowerKycSnapshot;
import com.finora.loan.domain.application.entity.LoanApplication;
import com.finora.loan.domain.application.entity.LoanApplicationStatusHistory;
import com.finora.loan.domain.application.enums.ActorType;
import com.finora.loan.domain.application.enums.LoanApplicationStatus;
import com.finora.loan.domain.application.enums.LoanPurpose;
import com.finora.loan.domain.core.entity.ScheduleCalculationSnapshot;
import com.finora.loan.domain.product.enums.RepaymentMethod;
import com.finora.loan.domain.scoring.enums.BorrowerKycStatus;
import com.finora.loan.dto.application.request.ConfirmLoanTermsRequest;
import com.finora.loan.dto.application.request.CreateLoanApplicationRequest;
import com.finora.loan.dto.application.request.DeclineLoanTermsRequest;
import com.finora.loan.dto.application.request.WithdrawLoanApplicationRequest;
import com.finora.loan.dto.application.response.LoanApplicationHistoryResponse;
import com.finora.loan.dto.application.response.LoanApplicationResponse;
import com.finora.loan.dto.application.response.LoanPurposeResponse;
import com.finora.loan.dto.response.PageResponse;
import com.finora.loan.exception.LoanBusinessException;
import com.finora.loan.integration.fineract.FineractIntegrationException;
import com.finora.loan.integration.fineract.client.FineractScheduleGateway;
import com.finora.loan.integration.fineract.contract.ScheduleCalculationRequest;
import com.finora.loan.integration.fineract.contract.ScheduleCalculationResult;
import com.finora.loan.integration.profile.BorrowerProfileUnavailableException;
import com.finora.loan.integration.profile.client.BorrowerProfileProvider;
import com.finora.loan.integration.profile.contract.BorrowerProfileResult;
import com.finora.loan.mapper.application.LoanApplicationMapper;
import com.finora.loan.repository.application.LoanApplicationRepository;
import com.finora.loan.repository.application.LoanApplicationStatusHistoryRepository;
import com.finora.loan.service.application.ApplicationPersistResult;
import com.finora.loan.service.application.ApplicationSubmissionContext;
import com.finora.loan.service.application.BorrowerKycSnapshots;
import com.finora.loan.service.application.LoanApplicationService;
import com.finora.loan.service.application.LoanApplicationSubmissionStateService;
import com.finora.loan.service.application.LoanTermsConfirmationService;
import com.finora.loan.service.contract.LoanContractStateService;
import com.finora.loan.service.funding.LoanFundingCancellationService;
import com.finora.loan.util.HashingService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
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

/**
 * Nghiệp vụ nộp và theo dõi hồ sơ vay của borrower.
 *
 * <p>Lời gọi Fineract và User nằm ngoài transaction; bước ghi hồ sơ do {@link LoanApplicationSubmissionStateService}
 * thực hiện trong transaction riêng.</p>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class LoanApplicationServiceImpl implements LoanApplicationService {

    private final LoanApplicationRepository loanApplicationRepository;
    private final LoanApplicationStatusHistoryRepository loanApplicationStatusHistoryRepository;
    private final LoanApplicationSubmissionStateService loanApplicationSubmissionStateService;
    private final FineractScheduleGateway fineractScheduleGateway;
    private final LoanApplicationMapper loanApplicationMapper;
    private final LoanTermsConfirmationService loanTermsConfirmationService;
    private final HashingService hashingService;
    private final LoanPricingDisclosureProperties loanPricingDisclosureProperties;
    private final BorrowerProfileProvider borrowerProfileProvider;
    private final LoanFundingCancellationService loanFundingCancellationService;
    private final LoanContractStateService loanContractStateService;
    private final Clock clock;

    @Override
    public LoanApplicationResponse submit(CreateLoanApplicationRequest request, String idempotencyKey) {
        String borrowerId = SecurityUtils.getCurrentUserId();
        validateDisclosure(request);
        String normalizedKey = idempotencyKey.trim();
        String requestHash = hashingService.sha256(request);

        // Kiểm tra idempotency trước khi gọi Fineract để request lặp không tính lịch và tạo hồ sơ lần hai.
        LoanApplication existing = loanApplicationSubmissionStateService.findExisting(borrowerId, normalizedKey)
                .orElse(null);
        if (existing != null) {
            return existingResponse(existing, requestHash);
        }

        // Chỉ người vay đã xác minh eKYC được nộp (D4); kiểm trước Fineract để không tính lịch cho hồ sơ không được nhận.
        BorrowerKycSnapshot kycSnapshot = requireVerifiedBorrower(borrowerId);

        // Chụp Product/mapping trước khi ra mạng; bước persist sẽ khóa và kiểm tra lại để chống Product đổi giữa chừng.
        ApplicationSubmissionContext context = loanApplicationSubmissionStateService.prepareContext(request);
        String scheduleRequestId = UUID.randomUUID().toString();
        ScheduleCalculationResult schedule;
        try {
            // Không mở transaction database trong lúc chờ Fineract để tránh giữ connection/lock lâu.
            schedule = fineractScheduleGateway.calculateSchedule(new ScheduleCalculationRequest(
                    context.productId(),
                    context.fineractProductId(),
                    request.requestedAmount(),
                    request.requestedTermMonths(),
                    context.annualInterestRate(),
                    context.repaymentMethod(),
                    context.coreConfigVersion(),
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
            ApplicationPersistResult result = loanApplicationSubmissionStateService.persist(
                    borrowerId,
                    normalizedKey,
                    requestHash,
                    scheduleRequestId,
                    loanPricingDisclosureProperties.pricingDisclosureVersion(),
                    request,
                    context,
                    schedule,
                    kycSnapshot
            );
            log.info("Đã nộp hồ sơ vay: applicationNumber={}, productId={}, borrowerId={}",
                    result.application().getApplicationNumber(), request.loanProductId(), borrowerId);
            return loanApplicationMapper.toResponse(result.application(), result.calculationSnapshot());
        } catch (DataIntegrityViolationException exception) {
            // Hai request cùng key có thể cùng vượt qua lần kiểm tra đầu; unique key là chốt chặn cuối.
            LoanApplication raced = loanApplicationSubmissionStateService.findExisting(borrowerId, normalizedKey)
                    .orElseThrow(() -> exception);
            return existingResponse(raced, requestHash);
        }
    }

    /**
     * Đọc eKYC của người vay ngoài transaction (provider {@code user} chuyển tiếp token của chính request) và chặn người
     * chưa {@code VERIFIED}. Nguồn không trả lời thì báo tạm thời không nộp được, không đoán trạng thái.
     */
    private BorrowerKycSnapshot requireVerifiedBorrower(String borrowerId) {
        BorrowerProfileResult profile;
        try {
            profile = borrowerProfileProvider.getBorrowerProfile(borrowerId, LocalDate.now(clock));
        } catch (BorrowerProfileUnavailableException exception) {
            log.warn("Chưa đọc được eKYC khi nộp hồ sơ: borrowerId={}, reason={}", borrowerId, exception.getReason());
            throw LoanBusinessException.serviceUnavailable(
                    "USER_PROFILE_UNAVAILABLE",
                    "Chưa kiểm tra được trạng thái xác minh danh tính. Vui lòng thử lại sau"
            );
        }
        if (profile.kycStatus() != BorrowerKycStatus.VERIFIED) {
            throw LoanBusinessException.conflict(
                    "BORROWER_KYC_REQUIRED",
                    "Bạn cần hoàn tất xác minh eKYC trước khi nộp hồ sơ vay"
            );
        }
        return BorrowerKycSnapshots.from(profile);
    }

    @Override
    @Transactional
    public LoanApplicationResponse withdraw(String applicationNumber, WithdrawLoanApplicationRequest request) {
        LoanApplication application = getApplication(applicationNumber);
        Instant now = Instant.now(clock);
        String userId = SecurityUtils.getCurrentUserId();
        LoanApplicationStatus previousStatus = application.getStatus();
        boolean openFunding = previousStatus == LoanApplicationStatus.APPROVED
                && application.getFundingStatus() == com.finora.loan.domain.application.enums.LoanFundingStatus.REQUESTED;
        if (openFunding) {
            application.validateRequestedFundingWithdrawal(request.version(), userId);
            // Đang gọi vốn bổ sung thì hồ sơ đã có hợp đồng chờ phát hành lại: đóng cùng transaction.
            loanContractStateService.closeRefundingOnBorrowerWithdrawal(application.getId(), userId, now);
            loanFundingCancellationService.cancel(
                    application.getId(), null, LoanFundingCancellationService.BORROWER_WITHDREW, now);
        } else {
            application.withdraw(request.version(), request.reason(), userId, now);
            loanApplicationRepository.saveAndFlush(application);
        }
        loanApplicationStatusHistoryRepository.save(LoanApplicationStatusHistory.create(
                application.getId(), previousStatus, application.getStatus(),
                openFunding ? "FUNDING_WITHDRAWN_BY_BORROWER" : "APPLICATION_WITHDRAWN",
                request.reason(), ActorType.BORROWER,
                userId, now));
        return loanApplicationMapper.toResponse(
                application,
                loanApplicationSubmissionStateService.getCalculation(application.getId()),
                loanApplicationSubmissionStateService.getFinalCalculation(application.getId())
        );
    }

    @Override
    public LoanApplicationResponse acceptTerms(
            String applicationNumber,
            ConfirmLoanTermsRequest request,
            String idempotencyKey
    ) {
        try {
            return loanTermsConfirmationService.accept(applicationNumber, idempotencyKey, request);
        } catch (DataIntegrityViolationException race) {
            // Unique idempotency key là chốt chặn cross-application; lần gọi mới trả
            // kết quả đã commit hoặc lỗi IDEMPOTENCY_KEY_REUSED có nghĩa.
            return loanTermsConfirmationService.accept(applicationNumber, idempotencyKey, request);
        }
    }

    @Override
    public LoanApplicationResponse declineTerms(
            String applicationNumber,
            DeclineLoanTermsRequest request,
            String idempotencyKey
    ) {
        try {
            return loanTermsConfirmationService.decline(applicationNumber, idempotencyKey, request);
        } catch (DataIntegrityViolationException race) {
            return loanTermsConfirmationService.decline(applicationNumber, idempotencyKey, request);
        }
    }

    @Override
    @Transactional(readOnly = true)
    public LoanApplicationResponse getMine(String applicationNumber) {
        LoanApplication application = getApplication(applicationNumber);
        application.requireOwner(SecurityUtils.getCurrentUserId());
        return loanApplicationMapper.toResponse(
                application,
                loanApplicationSubmissionStateService.getCalculation(application.getId()),
                loanApplicationSubmissionStateService.getFinalCalculation(application.getId())
        );
    }

    @Override
    @Transactional(readOnly = true)
    public PageResponse<LoanApplicationResponse> listMine(int page, int size) {
        Page<LoanApplicationResponse> result = loanApplicationRepository.findByBorrowerId(
                        SecurityUtils.getCurrentUserId(),
                        PageRequest.of(page, size, Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("id"))))
                .map(application -> loanApplicationMapper.toResponse(application, null));
        return PageResponse.from(result);
    }

    @Override
    @Transactional(readOnly = true)
    public PageResponse<LoanApplicationHistoryResponse> history(String applicationNumber, int page, int size) {
        LoanApplication application = getApplication(applicationNumber);
        application.requireOwner(SecurityUtils.getCurrentUserId());
        Page<LoanApplicationHistoryResponse> result = loanApplicationStatusHistoryRepository.findByLoanApplicationId(
                        application.getId(),
                        PageRequest.of(page, size, Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("id"))))
                .map(loanApplicationMapper::toHistoryResponse);
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
        return loanApplicationMapper.toResponse(
                existing,
                loanApplicationSubmissionStateService.getCalculation(existing.getId()),
                loanApplicationSubmissionStateService.getFinalCalculation(existing.getId())
        );
    }

    private void validateDisclosure(CreateLoanApplicationRequest request) {
        // Backend xác minh đúng phiên bản nội dung borrower đã xem, không chỉ tin một checkbox boolean.
        if (!Boolean.TRUE.equals(request.pricingDisclosureAccepted())) {
            throw LoanBusinessException.badRequest(
                    "PRICING_DISCLOSURE_NOT_ACCEPTED",
                    "Phải xác nhận đã xem lãi suất và lịch trả dự kiến"
            );
        }
        if (!loanPricingDisclosureProperties.pricingDisclosureVersion().equals(request.pricingDisclosureVersion())) {
            throw LoanBusinessException.conflict(
                    "PRICING_DISCLOSURE_OUTDATED",
                    "Nội dung công bố lãi suất đã thay đổi, vui lòng xem lại"
            );
        }
    }

    private LoanApplication getApplication(String applicationNumber) {
        return loanApplicationRepository.findByApplicationNumber(applicationNumber)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Loan Application", "applicationNumber", applicationNumber));
    }

}
