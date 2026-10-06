package com.finora.notification.service;

import com.finora.notification.domain.InAppNotification;
import com.finora.notification.messaging.InvestorNoteServicingChangedData;
import com.finora.notification.repository.InAppNotificationRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class InvestorServicingNotificationService {
    private final InAppNotificationRepository repository;
    private final Clock clock;

    @Transactional
    public void handle(UUID eventId, Instant occurredAt, InvestorNoteServicingChangedData data) {
        if (data == null || data.loanApplicationId() == null || data.changeType() == null
                || data.changeType().isBlank() || data.recipients() == null
                || data.recipients().isEmpty()) {
            throw new IllegalArgumentException("InvestorNoteServicingChanged data không hợp lệ");
        }
        Template template = template(data);
        java.util.Set<String> recipientsSeen = new java.util.HashSet<>();
        for (var recipient : data.recipients()) {
            if (recipient.investorId() == null || recipient.investorId().isBlank()
                    || !recipientsSeen.add(recipient.investorId())
                    || repository.existsBySourceEventIdAndRecipientIdAndType(
                    eventId, recipient.investorId(), data.changeType())) continue;
            repository.save(InAppNotification.create(eventId, recipient.investorId(),
                    data.changeType(), template.title(), template.message(),
                    data.loanApplicationId().toString(), data.externalPushRequired(),
                    occurredAt, clock.instant()));
        }
    }

    private Template template(InvestorNoteServicingChangedData data) {
        String loan = data.loanNumber() == null ? "khoản vay #" + data.loanApplicationId()
                : data.loanNumber();
        return switch (data.changeType()) {
            case "REPAYMENT_CREDITED" -> new Template("Đã nhận khoản trả nợ",
                    loan + " vừa phân bổ " + data.amount() + " VND vào các Note của bạn.");
            case "EARLY_SETTLEMENT" -> new Template("Khoản vay đã tất toán sớm",
                    loan + " đã tất toán sớm; gốc và lãi đã được phân bổ.");
            case "RESCHEDULED" -> new Template("Lịch trả nợ đã được cơ cấu",
                    loan + " có ngày đáo hạn mới " + data.maturityDate() + ".");
            case "SETTLED" -> new Template("Khoản vay đã tất toán",
                    loan + " đã hoàn tất nghĩa vụ thanh toán.");
            case "DELINQUENCY_CURED" -> new Template("Khoản vay đã khắc phục quá hạn",
                    loan + " đã trở về nhóm nợ " + data.debtGroup() + ".");
            case "BAD_DEBT_MILESTONE" -> new Template("Cập nhật rủi ro khoản vay",
                    loan + " đang ở nhóm nợ " + data.debtGroup() + ", DPD "
                            + data.daysPastDue() + ".");
            default -> new Template("Khoản vay đang quá hạn",
                    loan + " đang quá hạn " + data.daysPastDue() + " ngày.");
        };
    }

    private record Template(String title, String message) {}
}
