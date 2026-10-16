package com.finora.user.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Nhân viên từ chối hồ sơ eKYC; bắt buộc ghi lý do để đối chiếu khi người dùng khiếu nại. */
public record EkycRejectRequest(
        @NotBlank
        @Size(max = 1000)
        String note
) {
}
