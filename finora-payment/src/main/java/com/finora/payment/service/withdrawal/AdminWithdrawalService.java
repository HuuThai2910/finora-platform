package com.finora.payment.service.withdrawal;

import com.finora.common.dto.PageResponse;
import com.finora.payment.dto.response.AdminWithdrawalResponse;

import java.util.UUID;

/**
 * Hàng đợi sự cố rút tiền cho vận hành (R4): lệnh chưa có kết quả cuối, lâu nhất trước, và yêu cầu tra cứu lại provider
 * cho lệnh đã hết lượt tự động. Không thao tác nào ở đây gửi lệnh chi.
 */
public interface AdminWithdrawalService {

    /** Lệnh rút đang gửi provider, chờ provider hoặc cần đối soát, lâu chưa cập nhật nhất đứng trước. */
    PageResponse<AdminWithdrawalResponse> list(int page, int size);

    /**
     * Như trên, lọc theo chủ ví khi {@code ownerId} có giá trị (trang chi tiết khách hàng); rỗng thì như trên.
     */
    PageResponse<AdminWithdrawalResponse> list(String ownerId, int page, int size);

    /** Một lệnh rút theo mã, ở mọi trạng thái. */
    AdminWithdrawalResponse find(UUID withdrawalId);

    /**
     * Cho worker tra cứu lại provider ngay với lệnh đang {@code RECONCILIATION_REQUIRED} (đặt lại số lượt tra cứu tự
     * động); lệnh ở trạng thái khác trả {@code 409 WITHDRAWAL_NOT_RECONCILABLE}.
     */
    AdminWithdrawalResponse recheck(UUID withdrawalId);
}
