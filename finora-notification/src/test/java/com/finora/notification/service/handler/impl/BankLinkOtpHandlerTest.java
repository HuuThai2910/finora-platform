package com.finora.notification.service.handler.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.finora.notification.service.delivery.NotificationDraft;
import com.finora.notification.service.intake.EventPayloads;
import com.finora.notification.service.intake.HandlerOutcome;
import com.finora.notification.service.intake.InboundEvent;
import com.finora.notification.service.template.NotificationTemplate;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** BL-001: mã OTP chỉ nằm trong câu của hộp thư, không bao giờ lên push ở màn hình khóa. */
class BankLinkOtpHandlerTest {

    private final ObjectMapper objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());
    private final BankLinkOtpHandler handler = new BankLinkOtpHandler(new EventPayloads() {
        @Override
        public <T> T read(InboundEvent event, Class<T> type) {
            return objectMapper.convertValue(event.data(), type);
        }

        @Override
        public String write(InboundEvent event) {
            return event.data().toString();
        }

        @Override
        public InboundEvent withData(InboundEvent template, String payloadJson) {
            throw new UnsupportedOperationException();
        }
    });

    @Test
    void otpGoesToTheInboxOfTheOwnerButNotToThePush() {
        HandlerOutcome outcome = handler.handle(event("482913"));

        assertThat(outcome).isInstanceOfSatisfying(HandlerOutcome.Deliver.class, deliver -> {
            NotificationDraft draft = deliver.drafts().get(0);
            assertThat(draft.recipientId()).isEqualTo("42");
            assertThat(draft.template()).isEqualTo(NotificationTemplate.BANK_LINK_OTP);
            assertThat(draft.template().renderMessage(draft.values()))
                    .contains("482913", "Vietcombank", "******6789", "5 phút");
        });
        assertThat(NotificationTemplate.BANK_LINK_OTP.pushTitle() + NotificationTemplate.BANK_LINK_OTP.pushBody())
                .doesNotContainPattern("[0-9]");
    }

    @Test
    void malformedOtpIsAContractError() {
        assertThatThrownBy(() -> handler.handle(event("12ab"))).isInstanceOf(IllegalArgumentException.class);
    }

    private InboundEvent event(String otp) {
        Instant now = Instant.parse("2026-11-06T09:00:00Z");
        var data = objectMapper.createObjectNode()
                .put("linkRequestId", UUID.randomUUID().toString())
                .put("ownerId", "42")
                .put("bankCode", "VCB")
                .put("bankName", "Vietcombank")
                .put("accountNumberMasked", "******6789")
                .put("otp", otp)
                .put("expiresAt", now.plusSeconds(270).toString())
                .put("occurredAt", now.toString());
        return new InboundEvent(UUID.randomUUID(), "BankLinkOtpRequested", 1, now,
                "finora.payment.bank-link-otp-requested", data);
    }
}
