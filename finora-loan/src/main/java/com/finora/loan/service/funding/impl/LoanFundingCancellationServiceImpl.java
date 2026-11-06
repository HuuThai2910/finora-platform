package com.finora.loan.service.funding.impl;

import com.finora.loan.domain.application.entity.LoanApplication;
import com.finora.loan.exception.LoanDomainException;
import com.finora.loan.messaging.event.LoanFundingCancelledEventData;
import com.finora.loan.repository.application.LoanApplicationRepository;
import com.finora.loan.service.funding.LoanFundingCancellationService;
import com.finora.loan.service.outbox.OutboxService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

/**
 * Đóng đợt gọi vốn trước giải ngân và yêu cầu Investment bồi hoàn các khoản giữ liên quan.
 *
 * <p>Hồ sơ được khóa trước khi chuyển trạng thái gọi vốn nên event bồi hoàn chỉ được ghi một lần.</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LoanFundingCancellationServiceImpl implements LoanFundingCancellationService {

    private static final String EVENT_TYPE = "LoanFundingCancelled";
    private static final String SYSTEM_ACTOR = "SYSTEM-FUNDING-CANCELLATION";

    private final LoanApplicationRepository loanApplicationRepository;
    private final OutboxService outboxService;

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean cancel(Long applicationId, String contractNumber, String reasonCode, Instant now) {
        LoanApplication application = loanApplicationRepository.findByIdForUpdate(applicationId)
                .orElseThrow(() -> LoanDomainException.conflict(
                        "LOAN_APPLICATION_NOT_FOUND", "Không tìm thấy hồ sơ của đợt gọi vốn cần hủy"));
        if (!application.markFundingCancelled(reasonCode, SYSTEM_ACTOR, now)) {
            return false;
        }
        loanApplicationRepository.saveAndFlush(application);
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
        log.info("Hủy đợt gọi vốn trước giải ngân: applicationNumber={}, contractNumber={}, reasonCode={}",
                application.getApplicationNumber(), contractNumber, reasonCode);
        return true;
    }
}
