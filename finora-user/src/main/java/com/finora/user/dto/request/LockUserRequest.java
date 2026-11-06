package com.finora.user.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Khóa tài khoản: bắt buộc ghi lý do để người khác mở hồ sơ ra biết vì sao tài khoản bị khóa. */
public record LockUserRequest(
        @NotBlank(message = "Vui lòng nhập lý do khóa")
        @Size(max = 500, message = "Lý do khóa tối đa 500 ký tự")
        String reason
) {
}
