package com.finora.notification.domain;

/** Trạng thái thiết bị nhận push. */
public enum PushDeviceStatus {
    ACTIVE,
    /** Không nhận push nữa; token thô đã bị xóa. Đăng ký lại cùng token tạo dòng mới. */
    REVOKED
}
