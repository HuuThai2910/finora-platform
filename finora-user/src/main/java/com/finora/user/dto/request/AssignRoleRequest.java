package com.finora.user.dto.request;

import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Yêu cầu gán vai trò mới cho một người dùng.
 *
 * @see com.finora.user.domain.UserRole giá trị hợp lệ: BORROWER, INVESTOR, ADMIN
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class AssignRoleRequest {

    @NotBlank(message = "Vai trò không được để trống")
    private String role;
}
