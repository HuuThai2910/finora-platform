package com.finora.investment.client;

/**
 * Danh tính hiển thị của nhà đầu tư ở finora-user, dùng in lên giấy xác nhận chuyển nhượng Note.
 *
 * <p>Gọi bằng service account (worker không có JWT người dùng). Lỗi kết nối, token hay quyền ném
 * {@link UserIdentityUnavailableException} để worker hẹn lượt sau; không tự bịa họ tên.</p>
 */
public interface UserIdentityClient {

    UserIdentity identity(String userId);

    /**
     * @param fullName       họ tên theo hồ sơ; null khi người dùng chưa cập nhật
     * @param maskedIdNumber số CCCD đã che, chỉ còn 4 số cuối; null khi chưa có CCCD
     */
    record UserIdentity(String userId, String fullName, String maskedIdNumber) {
    }

    class UserIdentityUnavailableException extends RuntimeException {

        private final String code;

        public UserIdentityUnavailableException(String code, String message, Throwable cause) {
            super(message, cause);
            this.code = code;
        }

        public String code() {
            return code;
        }
    }
}
