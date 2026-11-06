package com.finora.user.service;

import com.finora.common.dto.PageResponse;
import com.finora.user.dto.request.UserSearchCriteria;
import com.finora.user.dto.response.UserProfileResponse;
import com.finora.user.dto.response.UserStatsResponse;
import org.springframework.data.domain.Pageable;

import java.util.Collection;
import java.util.UUID;

/**
 * Interface dịch vụ quản lý hồ sơ người dùng — xem hồ sơ và quản trị.
 * <p>
 * Không còn API cập nhật/khai CCCD tay: mọi thông tin định danh (họ tên, số
 * CCCD, ngày sinh...) được điền từ OCR khi quét eKYC.
 */
public interface UserProfileService {

    /**
     * Hồ sơ của chính người gọi, kèm quyền chức năng quản trị và vai trò nhân viên.
     *
     * @param keycloakUserId Keycloak ID của người gọi
     * @param authorities    authority của JWT đang dùng — nguồn của {@code permissions}
     */
    UserProfileResponse getMyProfile(UUID keycloakUserId, Collection<String> authorities);

    /**
     * Danh sách người dùng theo bộ lọc đầy đủ của màn quản trị: vai trò, trạng thái xác minh, vai trò nhân viên và
     * chuỗi tìm ({@code q}). Chuỗi tìm khớp chuỗi con của họ tên (không phân biệt dấu) hoặc email, hoặc khớp đúng số
     * điện thoại / số CCCD. Mới tạo trước.
     */
    PageResponse<UserProfileResponse> searchUsers(UserSearchCriteria criteria, Pageable pageable);

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
     * <p>
     * Ghi lý do khóa vào hồ sơ, tắt tài khoản trên Keycloak rồi đăng xuất mọi phiên đang mở.
     *
     * @param userId            ID người dùng bị khóa
     * @param reason            lý do khóa, bắt buộc
     * @param currentKeycloakId Keycloak ID của admin đang thao tác
     */
    void lockUser(Long userId, String reason, UUID currentKeycloakId);

    void unlockUser(Long userId);

    /**
     * Gán vai trò mới — nhận role dạng String, tự parse + kiểm tra admin không tự đổi role.
     * <p>
     * Trên Keycloak, realm role tài khoản cũ (và vai trò nhân viên cũ) được gỡ khi gán role mới, rồi mọi phiên
     * của người dùng bị đăng xuất để token sau mang đúng quyền mới.
     *
     * @param userId            ID người dùng
     * @param role              Tên vai trò dạng String (BORROWER, INVESTOR, STAFF, ADMIN)
     * @param staffRoleCode     mã vai trò nhân viên — bắt buộc khi {@code role=STAFF}, phải rỗng với vai trò khác
     * @param currentKeycloakId Keycloak ID của admin đang thao tác
     */
    void assignRole(Long userId, String role, String staffRoleCode, UUID currentKeycloakId);
}
