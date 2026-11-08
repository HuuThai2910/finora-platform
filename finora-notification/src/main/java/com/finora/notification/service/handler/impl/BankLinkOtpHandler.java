package com.finora.notification.service.handler.impl;

import com.finora.notification.messaging.event.PaymentWalletEvents;
import com.finora.notification.service.delivery.NotificationDraft;
import com.finora.notification.service.intake.EventKey;
import com.finora.notification.service.intake.EventPayloads;
import com.finora.notification.service.intake.HandlerOutcome;
import com.finora.notification.service.intake.InboundEvent;
import com.finora.notification.service.intake.InboundEventHandler;
import com.finora.notification.service.template.NotificationTemplate;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static com.finora.notification.service.handler.impl.HandlerChecks.required;

/**
 * Mã OTP liên kết ngân hàng (BL-001): {@code BankLinkOtpRequested.v1} của Payment. Ngân hàng giả lập không có SMS nên
 * FINORA đóng vai kênh SMS — mã nằm trong câu của hộp thư (chỉ chủ tài khoản đăng nhập mới đọc được); push trên màn
 * hình khóa không có mã. Người nhận là {@code ownerId} (ID hồ sơ).
 */
@Component
@RequiredArgsConstructor
public class BankLinkOtpHandler implements InboundEventHandler {

    static final EventKey BANK_LINK_OTP_REQUESTED = new EventKey("BankLinkOtpRequested", 1);

    private final EventPayloads eventPayloads;

    @Override
    public Set<EventKey> keys() {
        return Set.of(BANK_LINK_OTP_REQUESTED);
    }

    @Override
    public HandlerOutcome handle(InboundEvent event) {
        var payload = eventPayloads.read(event, PaymentWalletEvents.BankLinkOtpRequested.class);
        String linkRequestId = required(payload.linkRequestId(), "linkRequestId", event.eventType());
        String ownerId = required(payload.ownerId(), "ownerId", event.eventType());
        String otp = required(payload.otp(), "otp", event.eventType());
        if (!otp.matches("[0-9]{6}")) {
            throw new IllegalArgumentException(event.eventType() + " có otp không hợp lệ");
        }
        Map<String, String> values = Map.of(
                "otp", otp,
                "bank", payload.bankName() == null || payload.bankName().isBlank()
                        ? required(payload.bankCode(), "bankCode", event.eventType()) : payload.bankName().trim(),
                "account", payload.accountNumberMasked() == null || payload.accountNumberMasked().isBlank()
                        ? "" : payload.accountNumberMasked().trim(),
                "minutes", String.valueOf(minutesValid(payload)));
        return HandlerOutcome.deliver(List.of(NotificationDraft.of(ownerId, NotificationTemplate.BANK_LINK_OTP, values,
                linkRequestId)));
    }

    /** Làm tròn lên: mã sống 4 phút 30 giây ghi "5 phút" thay vì "4 phút" làm người dùng vội. */
    private static long minutesValid(PaymentWalletEvents.BankLinkOtpRequested payload) {
        if (payload.expiresAt() == null || payload.occurredAt() == null) {
            return 5;
        }
        long seconds = Math.max(60, Duration.between(payload.occurredAt(), payload.expiresAt()).getSeconds());
        return (seconds + 59) / 60;
    }
}
