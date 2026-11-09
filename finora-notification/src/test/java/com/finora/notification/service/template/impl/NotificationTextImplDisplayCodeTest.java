package com.finora.notification.service.template.impl;

import com.finora.notification.config.NotificationProperties;
import com.finora.notification.domain.entity.LoanReference;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Câu thông báo dùng mã hiển thị HS-/HĐ- khi đã nhận từ Loan; khoản vay cũ chưa có mã giữ nhãn LA-/LC- như trước. */
class NotificationTextImplDisplayCodeTest {

    private final NotificationTextImpl text = new NotificationTextImpl(mock(NotificationProperties.class));

    @Test
    void displayCodesWinOverLookupNumbers() {
        LoanReference reference = LoanReference.create(10L, UUID.randomUUID(), Instant.parse("2026-11-09T03:00:00Z"));
        reference.fillLabels("LA-10", "LC-10", null);
        reference.fillDisplayCodes("HS-20261109-3RXA", "HĐ-20261110-K7QM");

        assertThat(text.applicationLabel(10L, reference, "LA-10")).isEqualTo("HS-20261109-3RXA");
        assertThat(text.contractLabel(reference, "LC-10")).isEqualTo("HĐ-20261110-K7QM");
        assertThat(text.loanLabel(10L, reference, null, "LC-10", "LA-10")).isEqualTo("HĐ-20261110-K7QM");
        // Mã khoản vay servicing vẫn đứng đầu như trước.
        assertThat(text.loanLabel(10L, reference, "LN-10", "LC-10", "LA-10")).isEqualTo("LN-10");
    }

    @Test
    void loanWithoutDisplayCodesKeepsOldLabels() {
        LoanReference reference = LoanReference.create(10L, UUID.randomUUID(), Instant.parse("2026-11-09T03:00:00Z"));
        reference.fillLabels("LA-10", null, null);

        assertThat(text.applicationLabel(10L, reference, "LA-10")).isEqualTo("LA-10");
        assertThat(text.contractLabel(null, "LC-10")).isEqualTo("LC-10");
        assertThat(text.loanLabel(10L, null, null, null, null)).isEqualTo("khoản vay #10");
    }

    @Test
    void displayCodesAreOnlyFilledOnce() {
        LoanReference reference = LoanReference.create(10L, UUID.randomUUID(), Instant.parse("2026-11-09T03:00:00Z"));
        reference.fillDisplayCodes("HS-20261109-3RXA", null);
        reference.fillDisplayCodes("HS-OTHER", "HĐ-20261110-K7QM");

        assertThat(reference.getApplicationCode()).isEqualTo("HS-20261109-3RXA");
        assertThat(reference.getContractCode()).isEqualTo("HĐ-20261110-K7QM");
    }
}
