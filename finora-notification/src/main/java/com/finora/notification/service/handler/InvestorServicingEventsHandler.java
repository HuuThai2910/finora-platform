package com.finora.notification.service.handler;

import static com.finora.notification.service.handler.HandlerChecks.hasText;
import static com.finora.notification.service.handler.HandlerChecks.loanId;
import static com.finora.notification.service.handler.HandlerChecks.loanReference;
import static com.finora.notification.service.handler.HandlerChecks.notEmpty;
import static com.finora.notification.service.handler.HandlerChecks.required;

import com.finora.notification.domain.LoanReference;
import com.finora.notification.messaging.event.InvestorNoteServicingChangedData;
import com.finora.notification.service.delivery.NotificationDraft;
import com.finora.notification.service.intake.EventKey;
import com.finora.notification.service.intake.EventPayloads;
import com.finora.notification.service.intake.HandlerOutcome;
import com.finora.notification.service.intake.InboundEvent;
import com.finora.notification.service.intake.InboundEventHandler;
import com.finora.notification.service.recipient.LoanRecipientService;
import com.finora.notification.service.template.NotificationTemplate;
import com.finora.notification.service.template.NotificationText;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * {@code InvestorNoteServicingChanged.v1}: Investment đã fan-out theo chủ Note hiện tại (sau giao dịch chợ thứ cấp vẫn
 * đúng người). Giữ hành vi cũ (mỗi nhà đầu tư một tin, cộng phần tiền các Note của mình, {@code type = changeType},
 * {@code businessReference = loanApplicationId}) và sửa hai lỗi:
 * <ul>
 *   <li>B60: Investment phát {@code DELINQUENCY_CHANGED}/{@code BAD_DEBT_MILESTONE} mỗi lần DPD đổi, tức hằng ngày;
 *       trước đây mỗi lần là một tin trong hộp thư. Nay chỉ giữ tin Investment đánh dấu là mốc
 *       ({@code externalPushRequired}: đổi nhóm nợ từ nhóm 2 trở lên hoặc khắc phục).</li>
 *   <li>B61: loại thay đổi lạ trước đây rơi vào câu "đang quá hạn"; nay dùng câu trung tính.</li>
 * </ul>
 * Có push hay không theo cờ của Investment (D60): ví dụ tiền trả nợ hằng kỳ chỉ vào hộp thư, tất toán sớm thì push.
 */
@Component
@RequiredArgsConstructor
public class InvestorServicingEventsHandler implements InboundEventHandler {

    static final EventKey NOTE_SERVICING_CHANGED = new EventKey("InvestorNoteServicingChanged", 1);
    private static final Pattern CHANGE_TYPE = Pattern.compile("[A-Z][A-Z0-9_]{1,49}");

    private final EventPayloads payloads;
    private final LoanRecipientService recipients;
    private final NotificationText text;

    @Override
    public Set<EventKey> keys() {
        return Set.of(NOTE_SERVICING_CHANGED);
    }

    @Override
    public HandlerOutcome handle(InboundEvent event) {
        var data = payloads.read(event, InvestorNoteServicingChangedData.class);
        Long loanId = loanId(data.loanApplicationId(), event.eventType());
        String changeType = required(data.changeType(), "changeType", event.eventType());
        notEmpty(data.recipients(), "recipients", event.eventType());
        boolean dailyDelinquencyTick = ("DELINQUENCY_CHANGED".equals(changeType)
                || "BAD_DEBT_MILESTONE".equals(changeType)) && !data.externalPushRequired();
        if (dailyDelinquencyTick) return HandlerOutcome.filtered("DELINQUENCY_NOT_MILESTONE");

        NotificationTemplate template = template(changeType);
        String type = template == NotificationTemplate.INVESTOR_LOAN_UPDATE && CHANGE_TYPE.matcher(changeType).matches()
                ? changeType : template.name();
        LoanReference reference = recipients.find(loanId).orElse(null);
        String loan = capitalized(text.loanLabel(loanId, reference, data.loanNumber(), null, null));
        List<NotificationDraft> drafts = new ArrayList<>();
        for (Map.Entry<String, AmountShare> entry : amountsByInvestor(data).entrySet()) {
            Map<String, String> values = new LinkedHashMap<>();
            values.put("loan", loan);
            values.put("dpd", Integer.toString(data.daysPastDue()));
            values.put("group", Integer.toString(data.debtGroup()));
            values.put("maturityDate", text.date(data.maturityDate()));
            if (template == NotificationTemplate.REPAYMENT_CREDITED || template == NotificationTemplate.EARLY_SETTLEMENT) {
                // Event cũ chưa có phần tiền từng Note thì rơi về số tổng của event như trước.
                String amount = entry.getValue().known() ? text.money(entry.getValue().total()) : text.money(data.amount());
                values.put("amount", required(amount, "amount", event.eventType()));
            }
            drafts.add(new NotificationDraft(entry.getKey(), template, type, values, loanReference(loanId),
                    data.externalPushRequired()));
        }
        return HandlerOutcome.deliver(drafts);
    }

    private static NotificationTemplate template(String changeType) {
        return switch (changeType) {
            case "REPAYMENT_CREDITED" -> NotificationTemplate.REPAYMENT_CREDITED;
            case "EARLY_SETTLEMENT" -> NotificationTemplate.EARLY_SETTLEMENT;
            case "SETTLED" -> NotificationTemplate.SETTLED;
            case "RESCHEDULED" -> NotificationTemplate.RESCHEDULED;
            case "RESCHEDULE_CONSENT_REQUESTED" -> NotificationTemplate.RESCHEDULE_CONSENT_REQUESTED;
            case "DELINQUENCY_CURED" -> NotificationTemplate.DELINQUENCY_CURED;
            case "DELINQUENCY_CHANGED" -> NotificationTemplate.DELINQUENCY_CHANGED;
            case "BAD_DEBT_MILESTONE" -> NotificationTemplate.BAD_DEBT_MILESTONE;
            default -> NotificationTemplate.INVESTOR_LOAN_UPDATE;
        };
    }

    /**
     * Một nhà đầu tư có thể giữ nhiều Note của cùng khoản vay: gộp về một thông báo và cộng phần tiền các Note đó để mỗi
     * người thấy đúng số mình nhận, không phải tổng của cả khoản vay.
     */
    private Map<String, AmountShare> amountsByInvestor(InvestorNoteServicingChangedData data) {
        Map<String, AmountShare> shares = new LinkedHashMap<>();
        for (var recipient : data.recipients()) {
            if (recipient == null || !hasText(recipient.investorId())) continue;
            BigDecimal amount = text.decimal(recipient.amount());
            shares.merge(recipient.investorId(), new AmountShare(amount == null ? BigDecimal.ZERO : amount,
                    amount != null), AmountShare::plus);
        }
        return shares;
    }

    private static String capitalized(String label) {
        return label.isEmpty() ? label : Character.toUpperCase(label.charAt(0)) + label.substring(1);
    }

    /** Tổng phần tiền của các Note; {@code known=false} nếu có Note thiếu phần tiền (event cũ). */
    private record AmountShare(BigDecimal total, boolean known) {
        AmountShare plus(AmountShare other) {
            return new AmountShare(total.add(other.total), known && other.known);
        }
    }
}
