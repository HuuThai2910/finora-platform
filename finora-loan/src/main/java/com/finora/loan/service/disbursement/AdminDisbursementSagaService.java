package com.finora.loan.service.disbursement;

import com.finora.loan.domain.disbursement.enums.DisbursementSagaStatus;
import com.finora.loan.dto.disbursement.response.AdminDisbursementSagaResponse;
import com.finora.loan.dto.response.PageResponse;

import java.util.UUID;

/**
 * Màn sự cố giải ngân của admin (R2): xem saga theo trạng thái và đưa saga REPAIR_REQUIRED về ghi core lại sau khi vận hành
 * đã kiểm Fineract theo số hợp đồng. Không có thao tác nào ở đây chuyển tiền hay sửa dư nợ.
 */
public interface AdminDisbursementSagaService {

    /** Saga theo trạng thái ({@code null} là REPAIR_REQUIRED), cập nhật lâu nhất trước. */
    PageResponse<AdminDisbursementSagaResponse> list(DisbursementSagaStatus status, int page, int size);

    /**
     * Saga giải ngân của đúng một hồ sơ (phần "Sau giải ngân" của trang hồ sơ vay); mỗi hồ sơ tối đa một saga.
     *
     * @param loanApplicationId ID số của hồ sơ vay
     * @param status trạng thái cần lọc; {@code null} là REPAIR_REQUIRED như danh sách chung
     */
    PageResponse<AdminDisbursementSagaResponse> listByApplication(Long loanApplicationId, DisbursementSagaStatus status);

    /** Đưa saga REPAIR_REQUIRED về ghi core lại; saga ở trạng thái khác trả 409. */
    AdminDisbursementSagaResponse requeue(UUID sagaId, String note);
}
