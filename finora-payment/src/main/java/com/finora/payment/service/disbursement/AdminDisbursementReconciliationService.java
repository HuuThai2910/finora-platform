package com.finora.payment.service.disbursement;

import com.finora.common.dto.PageResponse;
import com.finora.payment.dto.response.DisbursementReconciliationResponse;

import java.util.UUID;

/**
 * Màn sự cố giải ngân của admin (R2): lệnh chưa kết thúc và yêu cầu tra cứu lại provider cho lệnh đối soát đã hết lượt
 * tự động. Không có thao tác nào ở đây gọi chuyển tiền; worker đối soát chỉ tra cứu theo sagaId.
 */
public interface AdminDisbursementReconciliationService {

    /**
     * Lệnh giải ngân chưa kết thúc (đang gọi provider, chờ thử lại, cần đối soát), lâu chưa cập nhật nhất đứng trước.
     */
    PageResponse<DisbursementReconciliationResponse> list(int page, int size);

    /**
     * Như trên, lọc theo một hồ sơ vay khi {@code loanApplicationId} có giá trị (phần "Sau giải ngân" của trang hồ sơ);
     * {@code null} thì như trên.
     */
    PageResponse<DisbursementReconciliationResponse> list(Long loanApplicationId, int page, int size);

    /**
     * Một lệnh giải ngân theo sagaId ở mọi trạng thái, kể cả đã xong: vận hành tra lại lệnh đã đóng sự cố, drill Q13
     * kiểm provider chỉ được gọi một lần sau khi Payment chết giữa chừng.
     */
    DisbursementReconciliationResponse find(UUID sagaId);

    /**
     * Cho worker tra cứu lại provider ngay với lệnh đang {@code RECONCILIATION_REQUIRED} (đặt lại số lượt tra cứu tự
     * động); lệnh ở trạng thái khác trả {@code 409 DISBURSEMENT_NOT_RECONCILABLE}.
     */
    DisbursementReconciliationResponse requestRecheck(UUID sagaId);
}
