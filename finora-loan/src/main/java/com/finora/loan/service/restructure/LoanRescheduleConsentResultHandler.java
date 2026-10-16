package com.finora.loan.service.restructure;

import com.finora.loan.domain.messaging.ProcessedEvent;
import com.finora.loan.domain.restructure.LoanRescheduleRequest;
import com.finora.loan.domain.restructure.LoanRescheduleStatus;
import com.finora.loan.domain.servicing.FinoraLoan;
import com.finora.loan.messaging.event.LoanRescheduleConsentCompletedEventData;
import com.finora.loan.repository.messaging.ProcessedEventRepository;
import com.finora.loan.repository.restructure.LoanRescheduleRequestRepository;
import com.finora.loan.repository.servicing.FinoraLoanRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Mở bước gọi Fineract chỉ sau khi Investment xác nhận toàn bộ chủ Note đã đồng ý. */
@Service
@RequiredArgsConstructor
public class LoanRescheduleConsentResultHandler {
    private static final String EVENT_TYPE = "LoanRescheduleConsentCompleted";
    private static final int EVENT_VERSION = 1;

    private final ProcessedEventRepository processed;
    private final LoanRescheduleRequestRepository requests;
    private final FinoraLoanRepository loans;
    private final Clock clock;

    @Transactional
    public void handle(UUID eventId, Instant occurredAt, LoanRescheduleConsentCompletedEventData data) {
        if (processed.existsByEventId(eventId)) return;
        validate(data);
        LoanRescheduleRequest request = requests.findByRequestIdForUpdate(data.requestId())
                .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy yêu cầu cơ cấu của kết quả đồng ý"));
        FinoraLoan loan = loans.findByIdForUpdate(request.getFinoraLoanId()).orElseThrow();
        if (!loan.getLoanApplicationId().equals(data.loanApplicationId())) {
            throw new IllegalArgumentException("Kết quả đồng ý không khớp khoản vay");
        }
        if (request.getStatus() != LoanRescheduleStatus.PENDING_INVESTOR_CONSENT) {
            throw new IllegalArgumentException("Yêu cầu không còn ở bước chờ nhà đầu tư đồng ý");
        }
        Instant now = clock.instant();
        if (data.approved()) {
            loan.beginRestructuring(now);
            request.investorsApproved(now);
        } else {
            request.investorsRejected(now);
        }
        processed.save(ProcessedEvent.create(eventId, EVENT_TYPE, EVENT_VERSION, "finora-investment",
                request.getRequestId().toString(), occurredAt, now));
    }

    private static void validate(LoanRescheduleConsentCompletedEventData data) {
        if (data == null || data.requestId() == null || data.loanApplicationId() == null
                || data.requiredInvestors() <= 0 || data.approvedInvestors() < 0
                || data.approvedInvestors() > data.requiredInvestors() || data.resolvedAt() == null
                || (data.approved() && data.approvedInvestors() != data.requiredInvestors())) {
            throw new IllegalArgumentException("Kết quả đồng ý cơ cấu thiếu hoặc không nhất quán");
        }
    }
}
