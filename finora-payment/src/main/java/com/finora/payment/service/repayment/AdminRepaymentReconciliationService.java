package com.finora.payment.service.repayment;

import com.finora.common.dto.PageResponse;
import com.finora.payment.domain.repayment.enums.PaymentRepaymentStatus;
import com.finora.payment.dto.response.RepaymentReconciliationResponse;

import java.util.List;
import java.util.UUID;

/**
 * Màn đối soát & sự cố trả nợ của vận hành: khoản trả nợ kẹt ở bước ghi Fineract hoặc cần đối soát, tra cứu Fineract
 * theo mã giao dịch và ghi lại khoản mà core chưa có sau khoảng an toàn (B32, D28).
 */
public interface AdminRepaymentReconciliationService {

    /**
     * Khoản trả nợ đang sự cố, lâu chưa cập nhật nhất đứng trước.
     *
     * @param statuses lọc trong ba trạng thái sự cố; bỏ trống là cả ba. Web chỉ đọc việc còn mở ({@code CORE_POSTING},
     *                 {@code RECONCILIATION_REQUIRED}); {@code FAILED} là khoản đã hoàn tiền, không còn gì để xử lý.
     */
    PageResponse<RepaymentReconciliationResponse> list(List<PaymentRepaymentStatus> statuses, int page, int size);

    /** Chỉ tra cứu transaction theo external reference; tuyệt đối không POST repayment lần hai. */
    RepaymentReconciliationResponse reconcile(UUID repaymentId);

    /**
     * B32 (D28): ghi lại vào Fineract khoản trả nợ RECONCILIATION_REQUIRED mà core chưa có, sau khoảng an toàn. Tra cứu
     * theo externalId trước; core đã có thì chỉ ghi nhận kết quả. Không tạo giao dịch thứ hai vì dùng đúng externalId.
     */
    RepaymentReconciliationResponse repost(UUID repaymentId);
}
