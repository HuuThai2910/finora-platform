package com.finora.notification.service.handler;

import static com.finora.notification.service.handler.HandlerChecks.required;

import com.finora.notification.messaging.event.PaymentWalletEvents;
import com.finora.notification.service.delivery.NotificationDraft;
import com.finora.notification.service.intake.EventKey;
import com.finora.notification.service.intake.EventPayloads;
import com.finora.notification.service.intake.HandlerOutcome;
import com.finora.notification.service.intake.InboundEvent;
import com.finora.notification.service.intake.InboundEventHandler;
import com.finora.notification.service.template.NotificationTemplate;
import com.finora.notification.service.template.NotificationText;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Kết quả rút tiền (Payment Wave 3, R4): {@code WithdrawalCompleted.v1} / {@code WithdrawalFailed.v1}. Người nhận là
 * chủ ví ({@code ownerId} = ID hồ sơ, người vay hoặc nhà đầu tư). Thông báo không khẳng định tiền đã hoàn hay chưa khi
 * thất bại: Payment là nguồn chuẩn của số dư, câu chữ chỉ hướng người dùng xem lịch sử ví.
 */
@Component
@RequiredArgsConstructor
public class WithdrawalEventsHandler implements InboundEventHandler {

    static final EventKey WITHDRAWAL_COMPLETED = new EventKey("WithdrawalCompleted", 1);
    static final EventKey WITHDRAWAL_FAILED = new EventKey("WithdrawalFailed", 1);
    private static final Set<String> OWNER_TYPES = Set.of("BORROWER", "INVESTOR");

    private final EventPayloads payloads;
    private final NotificationText text;

    @Override
    public Set<EventKey> keys() {
        return Set.of(WITHDRAWAL_COMPLETED, WITHDRAWAL_FAILED);
    }

    @Override
    public HandlerOutcome handle(InboundEvent event) {
        var data = payloads.read(event, PaymentWalletEvents.WithdrawalResult.class);
        String withdrawalId = required(data.withdrawalId(), "withdrawalId", event.eventType());
        String ownerId = required(data.ownerId(), "ownerId", event.eventType());
        if (!OWNER_TYPES.contains(required(data.ownerType(), "ownerType", event.eventType()))) {
            throw new IllegalArgumentException(event.eventType() + " có ownerType không hợp lệ");
        }
        NotificationTemplate template = "WithdrawalCompleted".equals(event.eventType())
                ? NotificationTemplate.WITHDRAWAL_COMPLETED : NotificationTemplate.WITHDRAWAL_FAILED;
        Map<String, String> values = Map.of(
                "amount", required(text.money(data.amount()), "amount", event.eventType()),
                "bank", data.bankCode() == null || data.bankCode().isBlank() ? "ngân hàng" : data.bankCode().trim(),
                // Payment đã che số tài khoản; Notification không nhận và không lưu số đầy đủ.
                "account", data.accountNumberMasked() == null || data.accountNumberMasked().isBlank()
                        ? "đã liên kết" : data.accountNumberMasked().trim());
        return HandlerOutcome.deliver(List.of(NotificationDraft.of(ownerId, template, values, withdrawalId)));
    }
}
