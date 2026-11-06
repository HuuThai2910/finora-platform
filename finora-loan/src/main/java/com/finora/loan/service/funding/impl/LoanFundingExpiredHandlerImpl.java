package com.finora.loan.service.funding.impl;

import com.finora.loan.domain.application.entity.LoanApplication;
import com.finora.loan.domain.messaging.entity.ProcessedEvent;
import com.finora.loan.exception.LoanBusinessException;
import com.finora.loan.messaging.event.LoanFundingExpiredEventData;
import com.finora.loan.repository.application.LoanApplicationRepository;
import com.finora.loan.repository.messaging.ProcessedEventRepository;
import com.finora.loan.service.funding.LoanFundingExpiredHandler;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.UUID;

/**
 * Consumer transaction của {@code LoanFundingExpired.v1}: đóng bước chờ gọi vốn của hồ sơ.
 *
 * <p>Hồ sơ được khóa và đối chiếu số hồ sơ trước khi ghi; marker event ghi cùng transaction.</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LoanFundingExpiredHandlerImpl implements LoanFundingExpiredHandler {

    private static final String EVENT_TYPE = "LoanFundingExpired";
    private static final int EVENT_VERSION = 1;
    private static final String SYSTEM_ACTOR = "SYSTEM-INVESTMENT-EVENT";

    private final ProcessedEventRepository processedEventRepository;
    private final LoanApplicationRepository loanApplicationRepository;
    private final Clock clock;

    @Override
    @Transactional
    public void handle(UUID eventId, Instant occurredAt, LoanFundingExpiredEventData eventData) {
        if (processedEventRepository.existsByEventId(eventId)) {
            return;
        }
        validate(eventData);
        LoanApplication application = loanApplicationRepository.findByIdForUpdate(eventData.loanApplicationId())
                .orElseThrow(() -> LoanBusinessException.conflict(
                        "LOAN_APPLICATION_NOT_FOUND_FOR_FUNDING",
                        "Không tìm thấy hồ sơ ứng với kết quả gọi vốn"));
        if (!application.getApplicationNumber().equals(eventData.applicationNumber())) {
            throw LoanBusinessException.conflict(
                    "FUNDING_EVENT_APPLICATION_MISMATCH",
                    "Kết quả hết hạn gọi vốn không khớp hồ sơ");
        }
        Instant now = clock.instant();
        if (application.markFundingExpired(
                eventData.listingId(), eventData.fundingRound(), eventData.fundingClosedAt(), SYSTEM_ACTOR, now)) {
            loanApplicationRepository.saveAndFlush(application);
            log.info("Hồ sơ hết hạn gọi vốn: applicationNumber={}, listingId={}, fundingRound={}",
                    eventData.applicationNumber(), eventData.listingId(), eventData.fundingRound());
        }
        processedEventRepository.saveAndFlush(ProcessedEvent.create(
                eventId, EVENT_TYPE, EVENT_VERSION, "finora-investment",
                eventData.applicationNumber(), occurredAt, now));
    }

    private void validate(LoanFundingExpiredEventData eventData) {
        if (eventData == null || eventData.loanApplicationId() == null || eventData.listingId() == null
                || eventData.applicationNumber() == null || eventData.applicationNumber().isBlank()
                || eventData.fundingRound() == null || eventData.fundingClosedAt() == null
                || !"VND".equals(eventData.currency())) {
            throw new IllegalArgumentException("LoanFundingExpired thiếu hoặc sai trường bắt buộc");
        }
    }
}
