package com.finora.user.dto.request;

import jakarta.validation.constraints.Size;

/** Nhân viên duyệt hồ sơ eKYC chờ duyệt tay; ghi chú tuỳ chọn. */
public record EkycApproveRequest(
        @Size(max = 1000)
        String note
) {
}
