package com.finora.user.service;

import java.util.UUID;

/**
 * Theo dõi sự cố của finora-ai theo từng người dùng để mở lối gửi nhân viên duyệt tay.
 * <p>
 * Chỉ đếm lỗi hệ thống (AI không phục vụ được: timeout, 5xx, circuit mở, phản hồi rỗng). AI trả lời được —
 * kể cả khi từ chối ảnh hay bắt quay lại video — là AI vẫn chạy, nên chuỗi lỗi liên tiếp bị cắt.
 */
public interface EkycAiOutageService {

    /**
     * Ghi một lần AI lỗi.
     *
     * @return {@code true} nếu sau lần này người dùng đã được gửi hồ sơ cho nhân viên duyệt
     */
    boolean recordFailure(UUID keycloakUserId);

    /** AI vừa trả lời được: cắt chuỗi lỗi liên tiếp. */
    void recordSuccess(UUID keycloakUserId);

    /** Đã đủ số lần AI lỗi liên tiếp để được gửi nhân viên duyệt (server tự kiểm, không tin client). */
    boolean isManualReviewAvailable(UUID keycloakUserId);
}
