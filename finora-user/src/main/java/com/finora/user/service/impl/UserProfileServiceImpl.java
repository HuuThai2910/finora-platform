package com.finora.user.service.impl;

import com.finora.common.dto.PageResponse;
import com.finora.common.exception.BusinessException;
import com.finora.common.exception.ResourceNotFoundException;
import com.finora.user.domain.EkycStatus;
import com.finora.user.domain.UserProfile;
import com.finora.user.domain.UserRole;
import com.finora.user.dto.response.UserProfileResponse;
import com.finora.user.dto.response.UserStatsResponse;
import com.finora.user.mapper.UserProfileMapper;
import com.finora.user.repository.UserProfileRepository;
import com.finora.user.service.KeycloakAdminService;
import com.finora.user.service.UserProfileService;
import com.finora.user.support.PiiMasker;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Triển khai dịch vụ quản lý hồ sơ người dùng — xem, cập nhật, eKYC (CCCD) và quản trị.
 * <p>
 * Dữ liệu PII (số CCCD, số điện thoại) được lưu dưới dạng:
 * <ul>
 *   <li>HMAC-SHA256 hash — cho tra cứu trùng lặp (deterministic, không giải mã được)</li>
 *   <li>AES-GCM encrypted — CryptoConverter tự động mã hóa/giải mã khi JPA persist/load</li>
 * </ul>
 */
@Service
@Slf4j
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class UserProfileServiceImpl implements UserProfileService {

    private final UserProfileRepository userProfileRepository;
    private final UserProfileMapper userProfileMapper;
    private final KeycloakAdminService keycloakAdminService;

    // ── Hồ sơ cá nhân ──────────────────────────────────────────────

    @Override
    public UserProfileResponse getMyProfile(UUID keycloakUserId) {
        UserProfile profile = findByKeycloakUserIdOrThrow(keycloakUserId);
        return userProfileMapper.toResponse(profile);
    }

    // ── Quản trị (Admin) ────────────────────────────────────────────

    @Override
    public PageResponse<UserProfileResponse> getAllUsers(String role, String ekycStatus, Pageable pageable) {
        UserRole roleFilter = parseRole(role);
        EkycStatus ekycFilter = parseEkycStatus(ekycStatus);

        Page<UserProfile> page;
        if (roleFilter != null && ekycFilter != null) {
            page = userProfileRepository
                    .findByRoleAndEkycStatusOrderByCreatedAtDesc(roleFilter, ekycFilter, pageable);
        } else if (roleFilter != null) {
            page = userProfileRepository.findByRoleOrderByCreatedAtDesc(roleFilter, pageable);
        } else if (ekycFilter != null) {
            page = userProfileRepository.findByEkycStatusOrderByCreatedAtDesc(ekycFilter, pageable);
        } else {
            page = userProfileRepository.findAllByOrderByCreatedAtDesc(pageable);
        }

        return PageResponse.<UserProfileResponse>builder()
                .content(page.getContent().stream()
                        .map(this::toAdminResponse)
                        .toList())
                .page(page.getNumber())
                .size(page.getSize())
                .totalElements(page.getTotalElements())
                .totalPages(page.getTotalPages())
                .last(page.isLast())
                .build();
    }

    @Override
    public UserStatsResponse getUserStats() {
        return UserStatsResponse.builder()
                .total(userProfileRepository.count())
                .byRole(toCountMap(userProfileRepository.countGroupedByRole()))
                .byEkycStatus(toCountMap(userProfileRepository.countGroupedByEkycStatus()))
                .build();
    }

    /** Gom kết quả {@code [enum, count]} thành map khóa là tên enum. */
    private Map<String, Long> toCountMap(List<Object[]> rows) {
        Map<String, Long> result = new LinkedHashMap<>();
        for (Object[] row : rows) {
            if (row[0] != null) {
                result.put(((Enum<?>) row[0]).name(), ((Number) row[1]).longValue());
            }
        }
        return result;
    }

    /** Chuỗi rỗng, null hoặc "ALL" đều nghĩa là không lọc. */
    private UserRole parseRole(String role) {
        if (isNoFilter(role)) return null;
        try {
            return UserRole.valueOf(role.toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "Vai trò không hợp lệ: " + role);
        }
    }

    private EkycStatus parseEkycStatus(String status) {
        if (isNoFilter(status)) return null;
        try {
            return EkycStatus.valueOf(status.toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new BusinessException(HttpStatus.BAD_REQUEST,
                    "Trạng thái eKYC không hợp lệ: " + status);
        }
    }

    private boolean isNoFilter(String value) {
        return value == null || value.isBlank() || "ALL".equalsIgnoreCase(value);
    }

    @Override
    public UserProfileResponse getUserById(Long userId) {
        return toAdminResponse(findByIdOrThrow(userId));
    }

    /**
     * Map hồ sơ cho màn hình quản trị — kèm trạng thái khóa đọc từ Keycloak.
     * <p>
     * Keycloak mới là nguồn sự thật của việc bật/tắt tài khoản, DB service này
     * không lưu cờ đó nên phải tra cứu thêm khi admin xem.
     */
    private UserProfileResponse toAdminResponse(UserProfile profile) {
        UserProfileResponse response = userProfileMapper.toResponse(profile);
        Boolean enabled = keycloakAdminService.isUserEnabled(profile.getKeycloakUserId().toString());
        response.setLocked(enabled == null ? null : !enabled);
        return response;
    }

    @Override
    @Transactional
    public void lockUser(Long userId, UUID currentKeycloakId) {
        UserProfile profile = findByIdOrThrow(userId);
        preventSelfAction(profile, currentKeycloakId, "Không thể tự khóa tài khoản của chính mình");
        keycloakAdminService.disableUser(profile.getKeycloakUserId().toString());
        log.info("Admin đã khóa tài khoản userId={}, email={}",
                userId, PiiMasker.maskEmail(profile.getEmail()));
    }

    @Override
    @Transactional
    public void unlockUser(Long userId) {
        UserProfile profile = findByIdOrThrow(userId);
        keycloakAdminService.enableUser(profile.getKeycloakUserId().toString());
        log.info("Admin đã mở khóa tài khoản userId={}, email={}",
                userId, PiiMasker.maskEmail(profile.getEmail()));
    }

    @Override
    @Transactional
    public void assignRole(Long userId, String role, UUID currentKeycloakId) {
        if (role == null || role.isBlank()) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "Vai trò không được để trống");
        }

        UserRole newRole;
        try {
            newRole = UserRole.valueOf(role.toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "Vai trò không hợp lệ: " + role);
        }

        UserProfile profile = findByIdOrThrow(userId);
        preventSelfAction(profile, currentKeycloakId, "Không thể tự đổi vai trò của chính mình");

        keycloakAdminService.assignRole(
                profile.getKeycloakUserId().toString(), "ROLE_" + newRole.name());

        profile.setRole(newRole);
        userProfileRepository.save(profile);

        log.info("Admin đã gán role {} cho userId={}, email={}",
                newRole, userId, PiiMasker.maskEmail(profile.getEmail()));
    }

    /**
     * Ngăn admin thực hiện hành động lên chính mình (khóa, đổi role).
     */
    private void preventSelfAction(UserProfile target, UUID currentKeycloakId, String message) {
        if (currentKeycloakId.equals(target.getKeycloakUserId())) {
            throw new BusinessException(HttpStatus.FORBIDDEN, message);
        }
    }

    // ── Helper ──────────────────────────────────────────────────────

    private UserProfile findByKeycloakUserIdOrThrow(UUID keycloakUserId) {
        return userProfileRepository.findByKeycloakUserId(keycloakUserId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Hồ sơ người dùng", "keycloakUserId", keycloakUserId));
    }

    private UserProfile findByIdOrThrow(Long userId) {
        return userProfileRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Hồ sơ người dùng", "id", userId));
    }
}
