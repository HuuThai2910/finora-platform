package com.finora.user.controller;

import com.finora.common.dto.BaseResponse;
import com.finora.common.dto.PageResponse;
import com.finora.common.exception.BusinessException;
import com.finora.user.domain.UserRole;
import com.finora.user.dto.response.UserProfileResponse;
import com.finora.user.repository.UserProfileRepository;
import com.finora.user.security.SecurityUtils;
import com.finora.user.service.UserProfileService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * Controller quản trị người dùng — chỉ dành cho admin.
 * <p>
 * Bao gồm: xem danh sách, khóa/mở khóa tài khoản, gán vai trò.
 */
@RestController
@RequestMapping("/api/v1/admin/users")
@RequiredArgsConstructor
public class AdminUserController {

    private final UserProfileService userProfileService;
    private final UserProfileRepository userProfileRepository;

    // ── Danh sách người dùng ────────────────────────────────────────

    /**
     * Xem danh sách tất cả người dùng — phân trang.
     */
    @GetMapping
    @PreAuthorize("hasAuthority('user:admin:read_all')")
    public BaseResponse<PageResponse<UserProfileResponse>> getAllUsers(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {

        return BaseResponse.success(
                userProfileService.getAllUsers(PageRequest.of(page, size)));
    }

    // ── Khóa/mở khóa tài khoản ─────────────────────────────────────

    /**
     * Khóa tài khoản người dùng — vô hiệu hóa trên Keycloak.
     * Admin không được tự khóa chính mình.
     */
    @PostMapping("/{id}/lock")
    @PreAuthorize("hasAuthority('user:admin:lock')")
    public BaseResponse<String> lockUser(@PathVariable Long id) {
        preventSelfAction(id, "Không thể tự khóa tài khoản của chính mình");
        userProfileService.lockUser(id);
        return BaseResponse.success("Khóa tài khoản thành công");
    }

    /**
     * Mở khóa tài khoản người dùng — kích hoạt lại trên Keycloak.
     */
    @PostMapping("/{id}/unlock")
    @PreAuthorize("hasAuthority('user:admin:lock')")
    public BaseResponse<String> unlockUser(@PathVariable Long id) {
        userProfileService.unlockUser(id);
        return BaseResponse.success("Mở khóa tài khoản thành công");
    }

    // ── Gán vai trò ─────────────────────────────────────────────────

    /**
     * Gán vai trò mới cho người dùng.
     * Admin không được tự đổi vai trò của chính mình.
     */
    @PostMapping("/{id}/roles")
    @PreAuthorize("hasAuthority('user:admin:assign_role')")
    public BaseResponse<String> assignRole(
            @PathVariable Long id,
            @RequestBody Map<String, String> body) {

        preventSelfAction(id, "Không thể tự đổi vai trò của chính mình");

        String roleValue = body.get("role");
        if (roleValue == null || roleValue.isBlank()) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "Vai trò không được để trống");
        }

        UserRole newRole;
        try {
            newRole = UserRole.valueOf(roleValue.toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new BusinessException(HttpStatus.BAD_REQUEST,
                    "Vai trò không hợp lệ: " + roleValue);
        }

        userProfileService.assignRole(id, newRole);
        return BaseResponse.success("Gán vai trò thành công");
    }

    // ── Helper ──────────────────────────────────────────────────────

    /**
     * Ngăn admin thực hiện hành động lên chính mình (khóa, đổi role).
     * So sánh keycloakUserId từ JWT với keycloakUserId của target user.
     */
    private void preventSelfAction(Long targetUserId, String message) {
        var currentKeycloakUserId = SecurityUtils.getCurrentKeycloakUserId();
        userProfileRepository.findById(targetUserId).ifPresent(targetProfile -> {
            if (currentKeycloakUserId.equals(targetProfile.getKeycloakUserId())) {
                throw new BusinessException(HttpStatus.FORBIDDEN, message);
            }
        });
    }
}
