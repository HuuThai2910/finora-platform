package com.finora.investment.client.impl;

import com.finora.investment.client.PaymentClient;
import com.finora.investment.client.PaymentHoldResult;
import com.finora.investment.client.PaymentTransferResult;
import java.math.BigDecimal;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Bản giả lập finora-payment, dùng khi Payment Service chưa có API giữ tiền.
 */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "finora.investment.payment", name = "mode", havingValue = "stub")
public class StubPaymentClient implements PaymentClient {

    /** Hạn mức giả định cho mỗi nhà đầu tư khi gặp lần đầu. */
    private static final BigDecimal DEFAULT_BALANCE = new BigDecimal("500000000.00");

    private final Map<String, BigDecimal> availableBalances = new ConcurrentHashMap<>();
    private final Map<String, HeldFund> holds = new ConcurrentHashMap<>();

    /** Lần thanh toán từ tiền giữ đã thực hiện, khóa theo mã thanh toán để chống trùng lặp. */
    private final Map<String, Settlement> settlements = new ConcurrentHashMap<>();

    /** Phí nền tảng đã thu. Ví thật sẽ hạch toán vào sổ; bản giả lập chỉ cộng dồn. */
    private final Map<String, BigDecimal> collectedFees = new ConcurrentHashMap<>();

    private record HeldFund(String investorId, BigDecimal amount) {
    }

    private record Settlement(String holdReference, String sellerId, BigDecimal amount, BigDecimal fee) {
    }

    @Override
    public synchronized PaymentHoldResult hold(String investorId, BigDecimal amount, String orderReference) {
        HeldFund existing = holds.get(orderReference);
        if (existing != null) {
            return PaymentHoldResult.ok(orderReference);
        }

        boolean[] deducted = {false};
        availableBalances.compute(investorId, (key, current) -> {
            BigDecimal balance = current == null ? DEFAULT_BALANCE : current;
            if (balance.compareTo(amount) < 0) {
                return balance;
            }
            deducted[0] = true;
            return balance.subtract(amount);
        });

        if (!deducted[0]) {
            return PaymentHoldResult.rejected(
                    "INSUFFICIENT_BALANCE",
                    "Số dư ví không đủ để đặt lệnh đầu tư"
            );
        }

        holds.put(orderReference, new HeldFund(investorId, amount));
        log.info("Stub Payment giữ tiền: orderReference={}, amountScale={}", orderReference, amount.scale());
        return PaymentHoldResult.ok(orderReference);
    }

    @Override
    public synchronized void release(String holdReference, String orderReference) {
        HeldFund released = holds.remove(holdReference);
        if (released == null) {
            log.info("Stub Payment bỏ qua nhả tiền đã xử lý: orderReference={}", orderReference);
            return;
        }
        availableBalances.merge(released.investorId(), released.amount(), BigDecimal::add);
        log.info("Stub Payment nhả tiền: orderReference={}", orderReference);
    }

    @Override
    public synchronized PaymentTransferResult settleFromHold(
            String holdReference,
            String orderReference,
            String sellerId,
            BigDecimal amount,
            BigDecimal platformFee,
            String settlementReference) {

        // Gọi lại cùng mã thanh toán chỉ chuyển tiền một lần. Ví thật phải giữ đúng hợp đồng
        // này, nên bản giả lập cũng phải giữ — nếu không, bản demo chạy đúng mà bản thật sai.
        if (settlements.containsKey(settlementReference)) {
            log.info("Stub Payment bỏ qua thanh toán đã xử lý: settlementReference={}", settlementReference);
            return PaymentTransferResult.ok(settlementReference);
        }

        HeldFund held = holds.get(holdReference);
        if (held == null) {
            return PaymentTransferResult.rejected(
                    "PAYMENT_HOLD_NOT_FOUND", "Không tìm thấy khoản tiền giữ");
        }
        if (held.amount().compareTo(amount) < 0) {
            return PaymentTransferResult.rejected(
                    "PAYMENT_HOLD_INSUFFICIENT", "Khoản tiền giữ không đủ cho lần thanh toán này");
        }

        // Trừ vào phần đang giữ; phần còn lại vẫn giữ cho các lần khớp sau hoặc chờ nhả.
        holds.put(holdReference, new HeldFund(held.investorId(), held.amount().subtract(amount)));

        // Không dùng `merge`: người bán có thể chưa từng giao dịch nên chưa có bản ghi ví, và
        // `merge` sẽ chèn đúng số tiền nhận được thay vì cộng vào hạn mức khởi tạo — tức ví của
        // họ bị đặt lại về gần 0.
        BigDecimal proceeds = amount.subtract(platformFee);
        availableBalances.compute(sellerId, (key, current) ->
                (current == null ? DEFAULT_BALANCE : current).add(proceeds));
        collectedFees.merge("PLATFORM", platformFee, BigDecimal::add);
        settlements.put(settlementReference, new Settlement(holdReference, sellerId, amount, platformFee));

        log.info("Stub Payment thanh toán từ tiền giữ: settlementReference={}", settlementReference);
        return PaymentTransferResult.ok(settlementReference);
    }

    /** Số dư khả dụng hiện tại, chỉ để đối chiếu khi chạy thử. */
    public BigDecimal availableBalance(String investorId) {
        return availableBalances.getOrDefault(investorId, DEFAULT_BALANCE);
    }

    /** Tổng phí nền tảng đã thu, chỉ để đối chiếu khi chạy thử. */
    public BigDecimal collectedFees() {
        return collectedFees.getOrDefault("PLATFORM", BigDecimal.ZERO);
    }
}
