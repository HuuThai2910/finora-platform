package com.finora.user.service;

import com.finora.common.security.pin.PinScope;
import com.finora.user.dto.response.PinStatusResponse;
import com.finora.user.dto.response.PinTokenResponse;

import java.util.UUID;

/**
 * Mã PIN giao dịch — tạo, kiểm, đổi, đặt lại.
 */
public interface TransactionPinService {

    /** Thông tin máy khách để gửi kèm cảnh báo khi PIN bị khoá. */
    record ClientInfo(String ipAddress, String userAgent) {
    }

    PinStatusResponse status(UUID keycloakUserId);

    PinStatusResponse create(UUID keycloakUserId, String pin);

    PinTokenResponse verify(UUID keycloakUserId, String pin, PinScope scope, String requestKey,
            ClientInfo client);

    PinStatusResponse change(UUID keycloakUserId, String currentPin, String newPin, ClientInfo client);

    /**
     * Quên PIN: xác minh lại bằng mật khẩu tài khoản. Sai mật khẩu tính chung bộ
     * đếm với đăng nhập, để màn quên PIN không thành đường dò mật khẩu thứ hai.
     */
    PinStatusResponse reset(UUID keycloakUserId, String password, String newPin, ClientInfo client);
}
