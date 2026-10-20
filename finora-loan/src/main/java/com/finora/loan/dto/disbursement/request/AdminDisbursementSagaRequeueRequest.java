package com.finora.loan.dto.disbursement.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * @param note việc vận hành đã kiểm và đã sửa (ví dụ "Đã đối chiếu Fineract theo số hợp đồng, sửa product mapping"), lưu
 *             làm audit trên saga
 */
public record AdminDisbursementSagaRequeueRequest(@NotBlank @Size(max = 500) String note) {}
