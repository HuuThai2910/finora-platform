package com.finora.notification.integration.push;

/** Kết quả của một thông điệp tại provider, đã quy về quyết định của Notification. */
public enum PushOutcome {
    /** Provider đã nhận (ticket) hoặc đã chuyển tới APNs/FCM (receipt). */
    ACCEPTED,
    /** Token không còn hợp lệ: thu hồi thiết bị, không thử lại. */
    DEVICE_NOT_REGISTERED,
    /** Lỗi không sửa được bằng cách thử lại (thông điệp quá lớn, request sai). */
    PERMANENT_FAILURE,
    /** Lỗi tạm thời (mạng, 429/5xx, vượt tần suất, cấu hình provider đang sai): thử lại có giới hạn. */
    RETRYABLE_FAILURE
}
