package com.finora.user.service;

import com.finora.common.dto.PageResponse;
import com.finora.user.dto.response.UserProfileResponse;
import com.finora.user.dto.response.UserStatsResponse;
import org.springframework.data.domain.Pageable;

import java.util.UUID;

/**
 * Interface dịch vụ quản lý hồ sơ người dùng — xem hồ sơ và quản trị.
 * <p>
 * Không còn API cập nhật/khai CCCD tay: mọi thông tin định danh (họ tên, số
 * CCCD, ngày sinh...) được điền từ OCR khi quét eKYC.
 */
public interface UserProfileService {

    UserProfileResponse getMyProfile(UUID keycloakUserId);

    /**
     * Danh sách người dùng, lọc tùy chọn theo vai trò và trạng thái eKYC.
     * <p>
     * Lọc ở tầng DB để kết quả trải trên toàn bộ dữ liệu, không giới hạn trong
     * trang đang tải.
     *
     * @param role       tên vai trò, {@code null} hoặc rỗng là không lọc
     * @param ekycStatus tên trạng thái eKYC, {@code null} hoặc rỗng là không lọc
     */
    PageResponse<UserProfileResponse> getAllUsers(String role, String ekycStatus, Pageable pageable);

    /** Thống kê tổng số người dùng theo vai trò và trạng thái eKYC. */
    UserStatsResponse getUserStats();

    /**
     * Xem chi tiết hồ sơ một người dùng bất kỳ — dành cho admin.
     *
     * @param userId ID hồ sơ trong bảng {@code user_profiles}
     */
    UserProfileResponse getUserById(Long userId);

    /**
     * Khóa tài khoản — kiểm tra admin không tự khóa chính mình.
     *
     * @param userId            ID người dùng bị khóa
     * @param currentKeycloakId Keycloak ID của admin đang thao tác
     */
    void lockUser(Long userId, UUID currentKeycloakId);

    void unlockUser(Long userId);

    /**
     * Gán vai trò mới — nhận role dạng String, tự parse + kiểm tra admin không tự đổi role.
     *
     * @param userId            ID người dùng
     * @param role              Tên vai trò dạng String (BORROWER, INVESTOR, ADMIN)
     * @param currentKeycloakId Keycloak ID của admin đang thao tác
     */
    void assignRole(Long userId, String role, UUID currentKeycloakId);
}
