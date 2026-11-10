package com.finora.investment.integration.user.client;

import java.util.Collection;
import java.util.Map;
import java.util.Set;

/**
 * Danh tính hiển thị của nhà đầu tư ở finora-user, dùng in lên giấy xác nhận chuyển nhượng Note.
 *
 * <p>Gọi bằng service account (worker không có JWT người dùng). Lỗi kết nối, token hay quyền ném
 * {@link UserIdentityUnavailableException} để worker hẹn lượt sau; không tự bịa họ tên.</p>
 */
public interface UserIdentityClient {

    UserIdentity identity(String userId);

    /**
     * Họ tên của nhiều người dùng trong một lời gọi (danh sách gọi vốn), không có CCCD.
     *
     * @return họ tên theo đúng ID đã gửi; ID không có hồ sơ không có trong map, người chưa có họ tên ánh xạ tới null
     */
    Map<String, String> fullNames(Collection<String> userIds);

    /**
     * ID của những người có họ tên chứa {@code query} (không phân biệt dấu), để Chợ chuyển nhượng lọc giao dịch và phần
     * vốn theo tên hai bên. Mỗi người góp cả ID hồ sơ lẫn Keycloak UUID vì Investment lưu nhà đầu tư theo một trong hai.
     *
     * @return tập ID (có thể rỗng); User chỉ trả tối đa 200 người mỗi lần
     */
    Set<String> userIdsByName(String query);

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
