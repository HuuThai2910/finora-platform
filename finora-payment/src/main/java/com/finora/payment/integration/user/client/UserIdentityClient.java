package com.finora.payment.integration.user.client;

/** Đọc danh tính đã xác minh của người dùng đang gọi Payment từ finora-user (BL-001). */
public interface UserIdentityClient {

    /** Họ tên và trạng thái eKYC của chính người dùng trong request hiện tại. */
    CurrentUser currentUser();

    /**
     * @param userId     ID hồ sơ (cùng giá trị claim {@code user_id})
     * @param fullName   họ tên trong hồ sơ, {@code null} khi chưa có
     * @param ekycStatus {@code PENDING | VERIFIED | MANUAL_REVIEW | FAILED}
     */
    record CurrentUser(String userId, String fullName, String ekycStatus) {
        public boolean ekycVerified() {
            return "VERIFIED".equals(ekycStatus);
        }
    }
}
