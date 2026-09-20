package com.finora.investment.client;

import static org.assertj.core.api.Assertions.assertThat;

import com.finora.investment.client.impl.StubPaymentClient;
import com.finora.investment.client.PaymentHoldResult;
import java.math.BigDecimal;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Bản giả lập ví phải giữ đúng hợp đồng idempotency mà Payment thật sẽ phải bảo đảm.
 */
class StubPaymentClientTest {

    @Test
    @DisplayName("Giữ tiền hai lần cùng mã lệnh chỉ trừ ví một lần")
    void holdIsIdempotent() {
        StubPaymentClient client = new StubPaymentClient();
        BigDecimal amount = new BigDecimal("10000000.00");

        PaymentHoldResult first = client.hold("INVESTOR-001", amount, "IO-1");
        PaymentHoldResult second = client.hold("INVESTOR-001", amount, "IO-1");

        assertThat(first.isSuccess()).isTrue();
        assertThat(second.isSuccess()).isTrue();
        assertThat(second.getHoldReference()).isEqualTo(first.getHoldReference());
    }

    @Test
    @DisplayName("Vượt số dư thì từ chối và không phải lỗi tạm thời")
    void rejectsWhenBalanceInsufficient() {
        StubPaymentClient client = new StubPaymentClient();

        PaymentHoldResult result = client.hold(
                "INVESTOR-002", new BigDecimal("900000000.00"), "IO-2");

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.isRetryable()).isFalse();
        assertThat(result.getErrorCode()).isEqualTo("INSUFFICIENT_BALANCE");
    }

    @Test
    @DisplayName("Nhả tiền hai lần không cộng tiền thừa vào ví")
    void releaseIsIdempotent() {
        StubPaymentClient client = new StubPaymentClient();
        BigDecimal amount = new BigDecimal("400000000.00");

        client.hold("INVESTOR-003", amount, "IO-3");
        client.release("IO-3", "IO-3");
        client.release("IO-3", "IO-3");

        assertThat(client.hold("INVESTOR-003", amount, "IO-4").isSuccess()).isTrue();
        assertThat(client.hold("INVESTOR-003", amount, "IO-5").isSuccess()).isFalse();
    }
}
