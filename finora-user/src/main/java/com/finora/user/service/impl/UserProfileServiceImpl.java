package com.finora.user.service.impl;

import com.finora.common.dto.PageResponse;
import com.finora.common.exception.BusinessException;
import com.finora.common.exception.ResourceNotFoundException;
import com.finora.user.config.CryptoProperties;
import com.finora.user.domain.UserProfile;
import com.finora.user.domain.UserRole;
import com.finora.user.dto.request.CccdDataRequest;
import com.finora.user.dto.request.UpdateProfileRequest;
import com.finora.user.dto.response.UserProfileResponse;
import com.finora.user.mapper.UserProfileMapper;
import com.finora.user.repository.UserProfileRepository;
import com.finora.user.service.KeycloakAdminService;
import com.finora.user.service.UserProfileService;
import com.finora.user.support.CryptoUtils;
import com.finora.user.support.PiiMasker;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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
    private final CryptoProperties cryptoProperties;
    private final KeycloakAdminService keycloakAdminService;

    // ── Hồ sơ cá nhân ──────────────────────────────────────────────

    @Override
    public UserProfileResponse getMyProfile(UUID keycloakUserId) {
        UserProfile profile = findByKeycloakUserIdOrThrow(keycloakUserId);
        return userProfileMapper.toResponse(profile);
    }

    @Override
    @Transactional
    public UserProfileResponse updateMyProfile(UUID keycloakUserId, UpdateProfileRequest request) {
        UserProfile profile = findByKeycloakUserIdOrThrow(keycloakUserId);

        userProfileMapper.updateFromRequest(request, profile);

        if (request.getPhone() != null) {
            String phoneHash = CryptoUtils.hmacSha256(request.getPhone(), cryptoProperties.getHmacSecret());

            if (userProfileRepository.existsByPhoneHash(phoneHash)
                    && !phoneHash.equals(profile.getPhoneHash())) {
                throw new BusinessException(HttpStatus.CONFLICT,
                        "Số điện thoại này đã được đăng ký trong hệ thống");
            }

            profile.setPhoneHash(phoneHash);
            profile.setPhoneEncrypted(request.getPhone());
        }

        updateProfileCompleteness(profile);

        profile = userProfileRepository.save(profile);

        log.info("Đã cập nhật hồ sơ: userId={}, email={}",
                profile.getId(), PiiMasker.maskEmail(profile.getEmail()));

        return userProfileMapper.toResponse(profile);
    }

    // ── eKYC — CCCD ─────────────────────────────────────────────────

    @Override
    @Transactional
    public UserProfileResponse submitCccdData(UUID keycloakUserId, CccdDataRequest request) {
        String idNumberHash = CryptoUtils.hmacSha256(
                request.getIdNumber(), cryptoProperties.getHmacSecret());

        if (userProfileRepository.existsByIdNumberHash(idNumberHash)) {
            UserProfile existing = userProfileRepository.findByKeycloakUserId(keycloakUserId)
                    .orElse(null);
            if (existing == null || !idNumberHash.equals(existing.getIdNumberHash())) {
                throw new BusinessException(HttpStatus.CONFLICT,
                        "Số CCCD này đã được đăng ký trong hệ thống");
            }
        }

        UserProfile profile = findByKeycloakUserIdOrThrow(keycloakUserId);

        profile.setFullName(request.getFullName());
        profile.setDateOfBirth(request.getDateOfBirth());
        profile.setGender(request.getGender());
        profile.setPlaceOfOrigin(request.getPlaceOfOrigin());
        profile.setAddress(request.getAddress());
        profile.setIdNumberHash(idNumberHash);
        profile.setIdNumberEncrypted(request.getIdNumber());

        updateProfileCompleteness(profile);

        profile = userProfileRepository.save(profile);

        log.info("Đã cập nhật CCCD cho userId={}, idNumber={}",
                profile.getId(), PiiMasker.maskIdNumber(request.getIdNumber()));

        return userProfileMapper.toResponse(profile);
    }

    // ── Quản trị (Admin) ────────────────────────────────────────────

    @Override
    public PageResponse<UserProfileResponse> getAllUsers(Pageable pageable) {
        Page<UserProfile> page = userProfileRepository.findAllByOrderByCreatedAtDesc(pageable);

        return PageResponse.<UserProfileResponse>builder()
                .content(page.getContent().stream()
                        .map(userProfileMapper::toResponse)
                        .toList())
                .page(page.getNumber())
                .size(page.getSize())
                .totalElements(page.getTotalElements())
                .totalPages(page.getTotalPages())
                .last(page.isLast())
                .build();
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

    private void updateProfileCompleteness(UserProfile profile) {
        boolean isComplete = profile.getFullName() != null && !profile.getFullName().isBlank()
                && profile.getIdNumberHash() != null && !profile.getIdNumberHash().isBlank()
                && profile.getPhoneHash() != null && !profile.getPhoneHash().isBlank();
        profile.setProfileCompleted(isComplete);
    }

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
