package com.finora.loan.service.application;

import java.time.Instant;

/**
 * Sinh mã hồ sơ vay: mã tra cứu {@code LA-} + 20 ký tự hex viết hoa (khóa API/event) và mã hiển thị
 * {@code HS-yyyyMMdd-XXXX} cho người dùng.
 */
public interface ApplicationNumberGenerator {

    /** Mã hồ sơ vay mới. */
    String next();

    /**
     * Mã hiển thị chưa có hồ sơ nào dùng, theo ngày Việt Nam của {@code createdAt}. Gọi trong transaction ghi hồ sơ; ràng
     * buộc {@code uq_loan_applications_application_code} vẫn chặn trường hợp hai request cùng lúc chọn trùng mã.
     */
    String nextCode(Instant createdAt);
}
