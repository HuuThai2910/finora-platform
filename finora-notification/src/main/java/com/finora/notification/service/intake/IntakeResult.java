package com.finora.notification.service.intake;

/** Kết quả nhận một event, dùng cho log và test. */
public enum IntakeResult {
    DELIVERED,
    /** Cùng eventId đã xử lý xong trước đó. */
    DUPLICATE,
    /** Chờ người nhận ở bảng chờ. */
    DEFERRED,
    FILTERED,
    NO_RECIPIENT,
    /** Hết thời gian chờ, đã gửi cho người đã biết. */
    EXPIRED_PARTIAL,
    /** Loại event đã biết nhưng version cũ không còn được phát (ví dụ LoanFullyFunded v1): bỏ qua. */
    UNSUPPORTED_VERSION
}
