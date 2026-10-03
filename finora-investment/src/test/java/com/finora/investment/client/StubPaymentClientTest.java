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

    @Test
    @DisplayName("Thanh toán từ tiền giữ: người bán nhận sau phí, phần giữ còn lại nhả về đúng người mua")
    void settleFromHoldSplitsFeeAndKeepsRemainder() {
        StubPaymentClient client = new StubPaymentClient();
        client.hold("BUYER-1", new BigDecimal("2000000.00"), "OB-1");

        PaymentTransferResult result = client.settleFromHold(
                "OB-1", "OB-1", "SELLER-1", new BigDecimal("950000.00"), new BigDecimal("47500.00"), "TRD-1");

        assertThat(result.isSuccess()).isTrue();
        assertThat(client.collectedFees()).isEqualByComparingTo("47500.00");
        assertThat(client.availableBalance("SELLER-1")).isEqualByComparingTo("500902500.00");

        // Nhả phần còn lại: người mua chỉ mất đúng 950.000đ so với hạn mức khởi tạo.
        client.release("OB-1", "OB-1");
        assertThat(client.availableBalance("BUYER-1")).isEqualByComparingTo("499050000.00");
    }

    @Test
    @DisplayName("Thanh toán hai lần cùng mã chỉ chuyển tiền và thu phí một lần")
    void settleFromHoldIsIdempotent() {
        StubPaymentClient client = new StubPaymentClient();
        client.hold("BUYER-2", new BigDecimal("2000000.00"), "OB-2");

        client.settleFromHold("OB-2", "OB-2", "SELLER-2", new BigDecimal("1000000.00"), new BigDecimal("50000.00"), "TRD-2");
        PaymentTransferResult replay = client.settleFromHold(
                "OB-2", "OB-2", "SELLER-2", new BigDecimal("1000000.00"), new BigDecimal("50000.00"), "TRD-2");

        assertThat(replay.isSuccess()).isTrue();
        assertThat(client.collectedFees()).isEqualByComparingTo("50000.00");
        assertThat(client.availableBalance("SELLER-2")).isEqualByComparingTo("500950000.00");
    }

    @Test
    @DisplayName("Thanh toán vượt phần đang giữ bị từ chối, không phải lỗi tạm thời")
    void settleFromHoldRejectsOverdraw() {
        StubPaymentClient client = new StubPaymentClient();
        client.hold("BUYER-3", new BigDecimal("1000.00"), "OB-3");

        PaymentTransferResult result = client.settleFromHold(
                "OB-3", "OB-3", "SELLER-3", new BigDecimal("1000.01"), BigDecimal.ZERO, "TRD-3");

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.isRetryable()).isFalse();
        assertThat(result.getErrorCode()).isEqualTo("PAYMENT_HOLD_INSUFFICIENT");
        assertThat(client.availableBalance("SELLER-3")).isEqualByComparingTo("500000000.00");
    }
}
