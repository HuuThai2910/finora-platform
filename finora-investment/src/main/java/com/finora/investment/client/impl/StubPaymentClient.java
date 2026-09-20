package com.finora.investment.client.impl;

import com.finora.investment.client.PaymentClient;
import com.finora.investment.client.PaymentHoldResult;
import java.math.BigDecimal;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Bản giả lập finora-payment, dùng khi Payment Service chưa có API giữ tiền.
 */
@Slf4j
@Component
public class StubPaymentClient implements PaymentClient {

    /** Hạn mức giả định cho mỗi nhà đầu tư khi gặp lần đầu. */
    private static final BigDecimal DEFAULT_BALANCE = new BigDecimal("500000000.00");

    private final Map<String, BigDecimal> availableBalances = new ConcurrentHashMap<>();
    private final Map<String, HeldFund> holds = new ConcurrentHashMap<>();

    private record HeldFund(String investorId, BigDecimal amount) {
    }

    @Override
    public PaymentHoldResult hold(String investorId, BigDecimal amount, String orderReference) {
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
    public void release(String holdReference, String orderReference) {
        HeldFund released = holds.remove(holdReference);
        if (released == null) {
            log.info("Stub Payment bỏ qua nhả tiền đã xử lý: orderReference={}", orderReference);
            return;
        }
        availableBalances.merge(released.investorId(), released.amount(), BigDecimal::add);
        log.info("Stub Payment nhả tiền: orderReference={}", orderReference);
    }
}
