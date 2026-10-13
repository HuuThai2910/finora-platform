package com.finora.loan.service.funding;

import com.finora.loan.domain.application.LoanApplication;
import com.finora.loan.domain.messaging.ProcessedEvent;
import com.finora.loan.exception.LoanBusinessException;
import com.finora.loan.messaging.event.LoanFundingExpiredEventData;
import com.finora.loan.repository.application.LoanApplicationRepository;
import com.finora.loan.repository.messaging.ProcessedEventRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Consumer transaction của {@code LoanFundingExpired.v1}: đóng bước chờ gọi vốn của hồ sơ.
 *
 * <p>Investment đã huỷ mọi phần vốn và tự nhả tiền giữ cho nhà đầu tư; Loan chỉ ghi kết quả để
 * borrower thấy "gọi vốn không thành công" thay vì chờ mãi, và không lập Contract.</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LoanFundingExpiredHandler {

    private static final String EVENT_TYPE = "LoanFundingExpired";
    private static final int EVENT_VERSION = 1;
    private static final String SYSTEM_ACTOR = "SYSTEM-INVESTMENT-EVENT";

    private final ProcessedEventRepository processedEventRepository;
    private final LoanApplicationRepository applicationRepository;
    private final Clock clock;

    @Transactional
    public void handle(UUID eventId, Instant occurredAt, LoanFundingExpiredEventData data) {
        if (processedEventRepository.existsByEventId(eventId)) {
            return;
        }
        validate(data);
        LoanApplication application = applicationRepository.findByIdForUpdate(data.loanApplicationId())
                .orElseThrow(() -> LoanBusinessException.conflict(
                        "LOAN_APPLICATION_NOT_FOUND_FOR_FUNDING",
                        "Không tìm thấy hồ sơ ứng với kết quả gọi vốn"));
        if (!application.getApplicationNumber().equals(data.applicationNumber())) {
            throw LoanBusinessException.conflict(
                    "FUNDING_EVENT_APPLICATION_MISMATCH",
                    "Kết quả hết hạn gọi vốn không khớp hồ sơ");
        }
        Instant now = clock.instant();
        if (application.markFundingExpired(
                data.listingId(), data.fundingRound(), data.fundingClosedAt(), SYSTEM_ACTOR, now)) {
            applicationRepository.saveAndFlush(application);
            log.info("Hồ sơ hết hạn gọi vốn: applicationNumber={}, listingId={}, fundingRound={}",
                    data.applicationNumber(), data.listingId(), data.fundingRound());
        }
        processedEventRepository.saveAndFlush(ProcessedEvent.create(
                eventId, EVENT_TYPE, EVENT_VERSION, "finora-investment",
                data.applicationNumber(), occurredAt, now));
    }

    private void validate(LoanFundingExpiredEventData data) {
        if (data == null || data.loanApplicationId() == null || data.listingId() == null
                || data.applicationNumber() == null || data.applicationNumber().isBlank()
                || data.fundingRound() == null || data.fundingClosedAt() == null
                || !"VND".equals(data.currency())) {
            throw new IllegalArgumentException("LoanFundingExpired thiếu hoặc sai trường bắt buộc");
        }
    }
}
