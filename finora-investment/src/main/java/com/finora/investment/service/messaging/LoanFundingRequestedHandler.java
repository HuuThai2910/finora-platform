package com.finora.investment.service.messaging;

import com.finora.investment.domain.messaging.ProcessedEvent;
import com.finora.investment.dto.request.CreateListingRequest;
import com.finora.investment.messaging.event.LoanFundingRequestedEventData;
import com.finora.investment.repository.ProcessedEventRepository;
import com.finora.investment.service.MarketListingService;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Consumer transaction: tạo listing và ghi processed marker cùng thành công hoặc cùng rollback. */
@Service
@RequiredArgsConstructor
public class LoanFundingRequestedHandler {

    private static final String EVENT_TYPE = "LoanFundingRequested";
    private static final int EVENT_VERSION = 1;

    private final ProcessedEventRepository processedEventRepository;
    private final MarketListingService listingService;
    private final Clock clock;

    @Transactional
    public void handle(UUID eventId, Instant occurredAt, LoanFundingRequestedEventData data) {
        if (processedEventRepository.existsByEventId(eventId)) {
            return;
        }
        validate(data);
        listingService.createListingFrom(CreateListingRequest.builder()
                .loanId(data.loanApplicationId())
                .applicationNumber(data.applicationNumber())
                .listingVersion(data.listingVersion())
                .fundingRound(data.fundingRound())
                .termsVersion(data.termsVersion())
                .termsHash(data.termsHash())
                .contractNumber(null)
                .productCode(data.productCode())
                .purpose(data.purpose())
                .region(data.borrowerRegion() == null || data.borrowerRegion().isBlank()
                        ? "Không công bố" : data.borrowerRegion().trim())
                .creditGrade(data.creditGrade())
                .creditScore(data.creditScore())
                .targetAmount(decimal(data.targetAmount(), "targetAmount"))
                .annualInterestRate(decimal(data.annualInterestRate(), "annualInterestRate"))
                .termMonths(data.termMonths())
                .repaymentMethod(data.repaymentMethod())
                .build());
        Instant now = clock.instant();
        processedEventRepository.save(ProcessedEvent.create(
                eventId, EVENT_TYPE, EVENT_VERSION, "finora-loan",
                data.applicationNumber(), occurredAt, now));
    }

    private void validate(LoanFundingRequestedEventData data) {
        if (data == null || data.loanApplicationId() == null || data.applicationNumber() == null
                || data.termMonths() == null) {
            throw new IllegalArgumentException("LoanFundingRequested thiếu trường bắt buộc");
        }
    }

    private BigDecimal decimal(String value, String field) {
        try {
            return new BigDecimal(value);
        } catch (RuntimeException exception) {
            throw new IllegalArgumentException(field + " không phải decimal hợp lệ", exception);
        }
    }
}
