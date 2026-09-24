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
    @DisplayName("Chuyển nhượng Note: người mua trả đủ giá, người bán nhận sau phí")
    void transferSplitsFeeFromSellerProceeds() {
        StubPaymentClient client = new StubPaymentClient();
        BigDecimal price = new BigDecimal("950000.00");
        BigDecimal fee = new BigDecimal("47500.00");

        PaymentTransferResult result = client.transfer("BUYER-1", "SELLER-1", price, fee, "NTRF-1");

        assertThat(result.isSuccess()).isTrue();
        assertThat(client.collectedFees()).isEqualByComparingTo("47500.00");

        // Người mua bị trừ đúng giá treo: số dư còn lại phải đủ cho 500tr trừ 950k.
        assertThat(client.hold("BUYER-1", new BigDecimal("499050000.00"), "H-1").isSuccess()).isTrue();

        // Người bán nhận 902.500đ, tức số dư thành 500tr + 902.500đ.
        StubPaymentClient other = new StubPaymentClient();
        other.transfer("BUYER-2", "SELLER-2", price, fee, "NTRF-2");
        assertThat(other.hold("SELLER-2", new BigDecimal("500902500.00"), "H-2").isSuccess()).isTrue();
        assertThat(other.hold("SELLER-2", new BigDecimal("1.00"), "H-3").isSuccess()).isFalse();
    }

    @Test
    @DisplayName("Chuyển tiền hai lần cùng mã giao dịch chỉ trừ ví một lần")
    void transferIsIdempotent() {
        StubPaymentClient client = new StubPaymentClient();
        BigDecimal price = new BigDecimal("1000000.00");
        BigDecimal fee = new BigDecimal("50000.00");

        client.transfer("BUYER-3", "SELLER-3", price, fee, "NTRF-3");
        PaymentTransferResult replay =
                client.transfer("BUYER-3", "SELLER-3", price, fee, "NTRF-3");

        assertThat(replay.isSuccess()).isTrue();
        // Phí chỉ thu một lần, không phải hai.
        assertThat(client.collectedFees()).isEqualByComparingTo("50000.00");
        // Ví người mua chỉ bị trừ một lần: còn đúng 499tr.
        assertThat(client.hold("BUYER-3", new BigDecimal("499000000.00"), "H-4").isSuccess()).isTrue();
    }

    @Test
    @DisplayName("Ví người mua không đủ tiền thì từ chối, không chuyển cho người bán")
    void transferRejectsWhenBuyerBalanceInsufficient() {
        StubPaymentClient client = new StubPaymentClient();

        PaymentTransferResult result = client.transfer(
                "BUYER-4", "SELLER-4",
                new BigDecimal("900000000.00"), new BigDecimal("45000000.00"), "NTRF-4");

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.isRetryable()).isFalse();
        assertThat(result.getErrorCode()).isEqualTo("INSUFFICIENT_BALANCE");
        assertThat(client.collectedFees()).isEqualByComparingTo("0");
        // Người bán không nhận gì: số dư vẫn đúng mức khởi tạo.
        assertThat(client.hold("SELLER-4", new BigDecimal("500000000.00"), "H-5").isSuccess()).isTrue();
    }
}
