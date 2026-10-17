package com.finora.notification.domain;

/** Trạng thái event đang chờ người nhận; event giải quyết xong thì dòng bị xóa, chỉ dòng hết hạn còn lại cho vận hành. */
public enum DeferredEventStatus {
    /** Worker còn thử lại tới {@code expiresAt}. */
    WAITING,
    /** Hết thời gian chờ; đã gửi cho người nhận đã biết (nếu có). Trạng thái chết, chỉ xem/điều tra. */
    EXPIRED
}
