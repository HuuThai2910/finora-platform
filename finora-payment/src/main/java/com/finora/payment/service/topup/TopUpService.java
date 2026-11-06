package com.finora.payment.service.topup;

import com.finora.payment.dto.response.TopUpResponse;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Nạp tiền vào ví qua provider nạp tiền (mock hoặc ZaloPay): tạo lệnh nạp idempotent, nhận callback ZaloPay có chữ ký
 * MAC và ghi có ví qua ledger khi thanh toán thành công.
 */
public interface TopUpService {

    /**
     * Tạo lệnh nạp cho người dùng hiện tại (số nguyên đồng, trong giới hạn cấu hình) rồi xin provider thông tin thanh
     * toán. Idempotent theo {@code idempotencyKey}; provider lỗi trả {@code 503 TOPUP_PROVIDER_UNAVAILABLE}.
     */
    TopUpResponse create(BigDecimal amount, String idempotencyKey);

    /** Lệnh nạp theo mã; lệnh của người khác trả {@code 403 TOPUP_ACCESS_DENIED}. */
    TopUpResponse get(UUID topUpId);

    /**
     * Nút "Giả lập thanh toán thành công" (tiền sandbox, LEGAL-PAYMENT-01). Chỉ tồn tại khi môi trường cho phép giả lập
     * và provider nạp tiền là mock (D37); ngoài ra trả 404 như endpoint không có. Lệnh đã quá hạn thanh toán thì không
     * ghi có được nữa.
     */
    TopUpResponse completeMock(UUID topUpId);

    /**
     * Callback ZaloPay: kiểm MAC bằng Key2 rồi ghi có ví cho đơn nạp tương ứng. Trả {@code false} khi chưa cấu hình
     * Key2 hoặc MAC sai.
     */
    boolean processZaloPayCallback(String data, String mac);
}
