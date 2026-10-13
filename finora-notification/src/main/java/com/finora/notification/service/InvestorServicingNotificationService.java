package com.finora.notification.service;

import com.finora.notification.domain.InAppNotification;
import com.finora.notification.messaging.InvestorNoteServicingChangedData;
import com.finora.notification.repository.InAppNotificationRepository;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
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
        // Một nhà đầu tư có thể giữ nhiều Note của cùng khoản vay: gộp về một thông báo, cộng
        // phần tiền của các Note đó để mỗi người thấy đúng số mình nhận chứ không phải tổng cả khoản.
        Map<String, BigDecimal> amountByInvestor = new LinkedHashMap<>();
        Map<String, Boolean> amountKnown = new LinkedHashMap<>();
        for (var recipient : data.recipients()) {
            if (recipient.investorId() == null || recipient.investorId().isBlank()) continue;
            String investorId = recipient.investorId();
            amountByInvestor.putIfAbsent(investorId, BigDecimal.ZERO);
            amountKnown.merge(investorId, recipient.amount() != null, Boolean::logicalAnd);
            if (recipient.amount() != null) {
                amountByInvestor.merge(investorId, new BigDecimal(recipient.amount()), BigDecimal::add);
            }
        }
        for (var entry : amountByInvestor.entrySet()) {
            String investorId = entry.getKey();
            if (repository.existsBySourceEventIdAndRecipientIdAndType(
                    eventId, investorId, data.changeType())) continue;
            // Event cũ chưa có phần tiền từng Note thì rơi về số tổng của event như trước.
            String amount = amountKnown.get(investorId)
                    ? entry.getValue().setScale(2, RoundingMode.HALF_UP).toPlainString()
                    : data.amount();
            Template template = template(data, amount);
            repository.save(InAppNotification.create(eventId, investorId,
                    data.changeType(), template.title(), template.message(),
                    data.loanApplicationId().toString(), data.externalPushRequired(),
                    occurredAt, clock.instant()));
        }
    }

    private Template template(InvestorNoteServicingChangedData data, String amount) {
        String loan = data.loanNumber() == null ? "khoản vay #" + data.loanApplicationId()
                : data.loanNumber();
        return switch (data.changeType()) {
            case "REPAYMENT_CREDITED" -> new Template("Đã nhận khoản trả nợ",
                    loan + " vừa phân bổ " + amount + " VND vào các Note của bạn.");
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
