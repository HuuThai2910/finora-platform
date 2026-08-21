package com.finora.user.service;

import com.finora.common.dto.PageResponse;
import com.finora.user.dto.response.UserProfileResponse;
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

    PageResponse<UserProfileResponse> getAllUsers(Pageable pageable);

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
