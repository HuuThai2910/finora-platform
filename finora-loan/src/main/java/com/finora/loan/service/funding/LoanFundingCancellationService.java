package com.finora.loan.service.funding;

import com.finora.loan.domain.application.LoanApplication;
import com.finora.loan.exception.LoanDomainException;
import com.finora.loan.messaging.event.LoanFundingCancelledEventData;
import com.finora.loan.repository.application.LoanApplicationRepository;
import com.finora.loan.service.outbox.OutboxService;
import java.time.Instant;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Bồi hoàn cho đợt gọi vốn đã đủ vốn nhưng khoản vay không được giải ngân.
 *
 * <p>Chạy trong cùng transaction với thay đổi nguồn (Contract chuyển DECLINED/EXPIRED hoặc Saga chuyển
 * PAYMENT_FAILED) để trạng thái gọi vốn và outbox cùng commit/rollback. Loan chỉ phát sự kiện; việc hủy
 * commitment thuộc Investment và việc trả tiền giữ thuộc Payment theo đúng SoR.</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LoanFundingCancellationService {

    public static final String CONTRACT_DECLINED = "CONTRACT_DECLINED";
    public static final String CONTRACT_EXPIRED = "CONTRACT_EXPIRED";
    public static final String DISBURSEMENT_FAILED = "DISBURSEMENT_FAILED";

    private static final String EVENT_TYPE = "LoanFundingCancelled";
    private static final String SYSTEM_ACTOR = "SYSTEM-FUNDING-CANCELLATION";

    private final LoanApplicationRepository applicationRepository;
    private final OutboxService outboxService;

    /**
     * Khóa hồ sơ, chuyển {@code FULLY_FUNDED -> CANCELLED} và ghi outbox publishable đúng một lần.
     *
     * @return {@code true} nếu vừa phát event bồi hoàn; {@code false} nếu đã hủy trước đó
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean cancel(Long applicationId, String contractNumber, String reasonCode, Instant now) {
        LoanApplication application = applicationRepository.findByIdForUpdate(applicationId)
                .orElseThrow(() -> LoanDomainException.conflict(
                        "LOAN_APPLICATION_NOT_FOUND", "Không tìm thấy hồ sơ của đợt gọi vốn cần hủy"));
        if (!application.markFundingCancelled(reasonCode, SYSTEM_ACTOR, now)) {
            return false;
        }
        applicationRepository.saveAndFlush(application);
        outboxService.recordForPublication(
                "LoanApplication",
                application.getApplicationNumber(),
                EVENT_TYPE,
                1,
                new LoanFundingCancelledEventData(
                        application.getId(),
                        application.getApplicationNumber(),
                        application.getInvestmentListingId(),
                        application.getFundingRound(),
                        contractNumber,
                        application.getAllocationVersion(),
                        application.getAllocationHash(),
                        reasonCode,
                        now,
                        application.getBorrowerId()));
        log.info("Hủy đợt gọi vốn đã đủ vốn: applicationNumber={}, contractNumber={}, reasonCode={}",
                application.getApplicationNumber(), contractNumber, reasonCode);
        return true;
    }
}
