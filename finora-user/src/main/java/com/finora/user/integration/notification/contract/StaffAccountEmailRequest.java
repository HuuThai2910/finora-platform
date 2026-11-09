package com.finora.user.integration.notification.contract;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Request gửi email thông tin đăng nhập cho tài khoản nhân viên mới — contract với finora-notification.
 * <p>
 * Mang mật khẩu tạm: chỉ đi qua HTTP nội bộ có token service account, không bao giờ ghi log hay đưa lên Kafka.
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class StaffAccountEmailRequest {

    private String email;
    private String fullName;
    /** Tên vai trò hiển thị, ví dụ "Quản trị viên" hoặc "Nhân viên (Thu hồi nợ)". */
    private String roleName;
    private String temporaryPassword;
}
