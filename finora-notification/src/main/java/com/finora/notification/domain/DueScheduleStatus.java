package com.finora.notification.domain;

/** Lịch nhắc của một khoản vay. */
public enum DueScheduleStatus {
    /** Còn kỳ tiếp theo cần nhắc. */
    ACTIVE,
    /** Khoản vay đã tất toán hoặc không còn kỳ: không nhắc nữa. */
    CLOSED
}
