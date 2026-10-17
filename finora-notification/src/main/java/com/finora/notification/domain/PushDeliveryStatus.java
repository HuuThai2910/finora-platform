package com.finora.notification.domain;

/** Trạng thái một lần gửi push tới một thiết bị; xem máy trạng thái ở {@link PushDelivery}. */
public enum PushDeliveryStatus {
    /** Chờ gửi (lần đầu, sau giờ nghỉ hoặc sau lỗi tạm thời). */
    PENDING,
    /** Worker đang giữ lease để gửi. */
    SENDING,
    /** Expo đã nhận (có ticket), chờ receipt. */
    SENT,
    /** Receipt báo Expo đã chuyển tới APNs/FCM; không chứng minh thiết bị đã hiển thị. */
    CONFIRMED,
    /** Lỗi vĩnh viễn với thiết bị này (DeviceNotRegistered, MessageTooBig, request sai). */
    FAILED,
    /** Hết lượt thử với lỗi tạm thời: dead-letter, chờ vận hành gửi lại. */
    DEAD,
    /** Không gửi vì thiết bị đã bị thu hồi trước lượt gửi. */
    SKIPPED
}
