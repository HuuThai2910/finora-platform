package com.finora.loan.domain.application;

/**
 * Trạng thái xác nhận điều khoản cuối, độc lập với quyết định duyệt hồ sơ.
 * Hồ sơ có thể đã APPROVED nhưng vẫn phải chờ borrower chấp nhận khi điều khoản bất lợi hơn.
 */
public enum TermsConfirmationStatus {
    AUTO_AUTHORIZED,
    PENDING,
    ACCEPTED,
    DECLINED,
    EXPIRED
}
