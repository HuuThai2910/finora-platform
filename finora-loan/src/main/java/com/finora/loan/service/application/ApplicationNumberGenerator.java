package com.finora.loan.service.application;

/**
 * Sinh mã hồ sơ vay dạng {@code LA-} và 20 ký tự hex viết hoa.
 */
public interface ApplicationNumberGenerator {

    /** Mã hồ sơ vay mới. */
    String next();
}
