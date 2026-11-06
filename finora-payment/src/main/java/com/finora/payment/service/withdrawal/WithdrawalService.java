package com.finora.payment.service.withdrawal;

import com.finora.common.dto.PageResponse;
import com.finora.payment.dto.request.CreateWithdrawalRequest;
import com.finora.payment.dto.response.WithdrawalPolicyResponse;
import com.finora.payment.dto.response.WithdrawalResponse;

import java.util.UUID;

/**
 * Nhận lệnh rút tiền của người dùng (R4, W3B): chính sách rút, tạo lệnh rút (kiểm policy, trừ ví bằng bút toán dự trữ)
 * và xem lệnh rút của chính mình. Gọi provider là việc của worker.
 */
public interface WithdrawalService {

    /**
     * Chính sách rút của người dùng hiện tại: tối thiểu, hạn mức ngày (đã dùng, còn lại), phí, số dư khả dụng và ngân
     * hàng nhận được hỗ trợ.
     */
    WithdrawalPolicyResponse policy();

    /**
     * Tạo lệnh rút {@code REQUESTED} sau khi kiểm policy và trừ ví bằng bút toán dự trữ. Idempotent theo
     * {@code idempotencyKey} của người dùng; cùng key khác nội dung trả {@code 409 WITHDRAWAL_IDEMPOTENCY_CONFLICT}.
     */
    WithdrawalResponse request(CreateWithdrawalRequest request, String idempotencyKey);

    /** Lệnh rút của người dùng hiện tại, mới nhất trước. */
    PageResponse<WithdrawalResponse> list(int page, int size);

    /** Lệnh rút theo mã; lệnh của người khác trả 404 như không tồn tại. */
    WithdrawalResponse get(UUID withdrawalId);
}
