package com.finora.notification.config;

/**
 * Cách gửi push (D62), cấu hình bằng {@code finora.notification.push.provider} ({@code NOTIFICATION_PUSH_PROVIDER}).
 */
public enum PushProviderType {
    /**
     * Mặc định: chỉ ghi log rằng push "đã gửi", không gọi Expo, không tới thiết bị. Là cấu hình giả lập nên
     * {@code staging}/{@code production} từ chối khởi động với giá trị này (D37).
     */
    LOG("LOG_MOCK"),
    /** Gửi thật tới {@code https://exp.host/--/api/v2/push/send}; chỉ bật khi đã có EAS project và thiết bị thật. */
    EXPO("EXPO"),
    /** Không tạo lần gửi push nào; thông báo trong app vẫn chạy. */
    DISABLED(null);

    private final String storageCode;

    PushProviderType(String storageCode) {
        this.storageCode = storageCode;
    }

    /** Giá trị lưu ở {@code notification_push_deliveries.provider} để phân biệt rõ bản giả lập với Expo thật. */
    public String storageCode() {
        return storageCode;
    }
}
