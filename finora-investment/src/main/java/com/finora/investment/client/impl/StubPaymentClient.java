package com.finora.investment.client.impl;

import com.finora.investment.client.PaymentClient;
import com.finora.investment.client.PaymentHoldResult;
import com.finora.investment.client.PaymentTransferResult;
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

    /** Giao dịch chuyển nhượng đã thực hiện, khóa theo mã giao dịch để chống trùng lặp. */
    private final Map<String, Transfer> transfers = new ConcurrentHashMap<>();

    /** Phí nền tảng đã thu. Ví thật sẽ hạch toán vào sổ; bản giả lập chỉ cộng dồn. */
    private final Map<String, BigDecimal> collectedFees = new ConcurrentHashMap<>();

    private record HeldFund(String investorId, BigDecimal amount) {
    }

    private record Transfer(String buyerId, String sellerId, BigDecimal price, BigDecimal fee) {
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

    @Override
    public PaymentTransferResult transfer(
            String buyerId,
            String sellerId,
            BigDecimal price,
            BigDecimal platformFee,
            String transferReference) {

        // Gọi lại cùng mã giao dịch chỉ chuyển tiền một lần. Ví thật phải giữ đúng hợp đồng
        // này, nên bản giả lập cũng phải giữ — nếu không, bản demo chạy đúng mà bản thật sai.
        if (transfers.containsKey(transferReference)) {
            log.info("Stub Payment bỏ qua chuyển tiền đã xử lý: transferReference={}", transferReference);
            return PaymentTransferResult.ok(transferReference);
        }

        BigDecimal proceeds = price.subtract(platformFee);

        boolean[] deducted = {false};
        availableBalances.compute(buyerId, (key, current) -> {
            BigDecimal balance = current == null ? DEFAULT_BALANCE : current;
            if (balance.compareTo(price) < 0) {
                return balance;
            }
            deducted[0] = true;
            return balance.subtract(price);
        });

        if (!deducted[0]) {
            return PaymentTransferResult.rejected(
                    "INSUFFICIENT_BALANCE",
                    "Số dư ví không đủ để mua Note này"
            );
        }

        // Người bán nhận phần sau khi trừ phí. Người mua đã bị trừ đúng giá treo, nên phần phí
        // nằm lại ở nền tảng — bản giả lập cộng dồn để đối chiếu, chưa có sổ thu thật.
        //
        // Không dùng `merge`: người bán có thể chưa từng giao dịch nên chưa có bản ghi ví, và
        // `merge` sẽ chèn đúng số tiền nhận được thay vì cộng vào hạn mức khởi tạo — tức ví của
        // họ bị đặt lại về gần 0.
        availableBalances.compute(sellerId, (key, current) ->
                (current == null ? DEFAULT_BALANCE : current).add(proceeds));
        collectedFees.merge("PLATFORM", platformFee, BigDecimal::add);
        transfers.put(transferReference, new Transfer(buyerId, sellerId, price, platformFee));

        log.info("Stub Payment chuyển tiền chuyển nhượng Note: transferReference={}, priceScale={}",
                transferReference, price.scale());
        return PaymentTransferResult.ok(transferReference);
    }

    /** Tổng phí nền tảng đã thu, chỉ để đối chiếu khi chạy thử. */
    public BigDecimal collectedFees() {
        return collectedFees.getOrDefault("PLATFORM", BigDecimal.ZERO);
    }
}
