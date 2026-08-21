package com.finora.user.service;

import com.finora.user.domain.PendingRegistration;

import java.util.Optional;

/**
 * Kho lưu tạm thông tin đăng ký trong lúc chờ người dùng xác thực OTP.
 * <p>
 * Bản ghi tự hết hạn theo TTL. Mật khẩu được mã hoá trước khi ghi và chỉ giải mã
 * đúng một lần lúc tạo tài khoản, sau đó bản ghi phải bị xoá ngay.
 */
public interface PendingRegistrationStore {

    /** Ghi đè bản ghi đang chờ của email này và làm mới TTL. */
    void save(PendingRegistration registration);

    /** Đọc bản ghi còn hiệu lực; rỗng nếu chưa từng đăng ký hoặc đã hết hạn. */
    Optional<PendingRegistration> find(String email);

    /** Xoá bản ghi sau khi tạo tài khoản thành công hoặc khi huỷ đăng ký. */
    void remove(String email);

    /** Thời gian sống của một phiên đăng ký tạm, tính bằng giây. */
    long ttlSeconds();
}
