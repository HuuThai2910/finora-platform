package com.finora.user.service;

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
import com.finora.user.util.CryptoUtils;
import com.finora.user.util.PiiMasker;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * Dịch vụ quản lý hồ sơ người dùng — xem, cập nhật, eKYC (CCCD) và quản trị.
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
public class UserProfileService {

    private final UserProfileRepository userProfileRepository;
    private final UserProfileMapper userProfileMapper;
    private final CryptoProperties cryptoProperties;
    private final KeycloakAdminService keycloakAdminService;

    // ── Hồ sơ cá nhân ──────────────────────────────────────────────

    /**
     * Xem hồ sơ của người dùng hiện tại.
     */
    public UserProfileResponse getMyProfile(UUID keycloakUserId) {
        UserProfile profile = findByKeycloakUserIdOrThrow(keycloakUserId);
        return userProfileMapper.toResponse(profile);
    }

    /**
     * Cập nhật hồ sơ cá nhân (partial update) — chỉ ghi đè trường khác null.
     * <p>
     * Nếu có số điện thoại mới, tính hash để kiểm tra trùng lặp
     * và lưu bản mã hóa (CryptoConverter tự xử lý).
     */
    @Transactional
    public UserProfileResponse updateMyProfile(UUID keycloakUserId, UpdateProfileRequest request) {
        UserProfile profile = findByKeycloakUserIdOrThrow(keycloakUserId);

        // Cập nhật các trường cơ bản (fullName, dateOfBirth, gender, placeOfOrigin, address)
        userProfileMapper.updateFromRequest(request, profile);

        // Xử lý số điện thoại riêng — cần hash để kiểm tra trùng lặp
        if (request.phone() != null) {
            String phoneHash = CryptoUtils.hmacSha256(request.phone(), cryptoProperties.hmacSecret());

            // Kiểm tra số điện thoại đã được đăng ký bởi người khác chưa
            if (userProfileRepository.existsByPhoneHash(phoneHash)
                    && !phoneHash.equals(profile.getPhoneHash())) {
                throw new BusinessException(HttpStatus.CONFLICT,
                        "Số điện thoại này đã được đăng ký trong hệ thống");
            }

            profile.setPhoneHash(phoneHash);
            // CryptoConverter tự mã hóa khi JPA persist
            profile.setPhoneEncrypted(request.phone());
        }

        // Kiểm tra hồ sơ đã đầy đủ chưa (fullName + CCCD + phone)
        updateProfileCompleteness(profile);

        profile = userProfileRepository.save(profile);

        log.info("Đã cập nhật hồ sơ: userId={}, email={}",
                profile.getId(), PiiMasker.maskEmail(profile.getEmail()));

        return userProfileMapper.toResponse(profile);
    }

    // ── eKYC — CCCD ─────────────────────────────────────────────────

    /**
     * Tiếp nhận dữ liệu CCCD người dùng tự khai — cập nhật hồ sơ eKYC.
     * <p>
     * Hash số CCCD để kiểm tra trùng lặp toàn hệ thống;
     * nếu trùng và thuộc người khác, từ chối (mỗi CCCD chỉ đăng ký một tài khoản).
     */
    @Transactional
    public UserProfileResponse submitCccdData(UUID keycloakUserId, CccdDataRequest request) {
        String idNumberHash = CryptoUtils.hmacSha256(
                request.idNumber(), cryptoProperties.hmacSecret());

        // Kiểm tra CCCD đã đăng ký bởi tài khoản khác chưa
        if (userProfileRepository.existsByIdNumberHash(idNumberHash)) {
            UserProfile existing = userProfileRepository.findByKeycloakUserId(keycloakUserId)
                    .orElse(null);
            // Cho phép nếu trùng nhưng thuộc chính user hiện tại (cập nhật lại)
            if (existing == null || !idNumberHash.equals(existing.getIdNumberHash())) {
                throw new BusinessException(HttpStatus.CONFLICT,
                        "Số CCCD này đã được đăng ký trong hệ thống");
            }
        }

        UserProfile profile = findByKeycloakUserIdOrThrow(keycloakUserId);

        // Cập nhật thông tin từ CCCD
        profile.setFullName(request.fullName());
        profile.setDateOfBirth(request.dateOfBirth());
        profile.setGender(request.gender());
        profile.setPlaceOfOrigin(request.placeOfOrigin());
        profile.setAddress(request.address());
        profile.setIdNumberHash(idNumberHash);
        // CryptoConverter tự mã hóa khi JPA persist
        profile.setIdNumberEncrypted(request.idNumber());

        // Kiểm tra hồ sơ đã đầy đủ chưa
        updateProfileCompleteness(profile);

        profile = userProfileRepository.save(profile);

        log.info("Đã cập nhật CCCD cho userId={}, idNumber={}",
                profile.getId(), PiiMasker.maskIdNumber(request.idNumber()));

        return userProfileMapper.toResponse(profile);
    }

    // ── Quản trị (Admin) ────────────────────────────────────────────

    /**
     * Danh sách tất cả người dùng — phân trang, sắp xếp theo thời gian tạo giảm dần.
     * Chỉ admin được gọi (controller kiểm tra quyền).
     */
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

    /**
     * Khóa tài khoản người dùng — vô hiệu hóa trên Keycloak (không thể đăng nhập).
     */
    @Transactional
    public void lockUser(Long userId) {
        UserProfile profile = findByIdOrThrow(userId);
        keycloakAdminService.disableUser(profile.getKeycloakUserId().toString());
        log.info("Admin đã khóa tài khoản userId={}, email={}",
                userId, PiiMasker.maskEmail(profile.getEmail()));
    }

    /**
     * Mở khóa tài khoản người dùng — kích hoạt lại trên Keycloak.
     */
    @Transactional
    public void unlockUser(Long userId) {
        UserProfile profile = findByIdOrThrow(userId);
        keycloakAdminService.enableUser(profile.getKeycloakUserId().toString());
        log.info("Admin đã mở khóa tài khoản userId={}, email={}",
                userId, PiiMasker.maskEmail(profile.getEmail()));
    }

    /**
     * Gán vai trò mới cho người dùng — cập nhật cả Keycloak và DB.
     * Admin không được tự đổi vai trò của chính mình.
     */
    @Transactional
    public void assignRole(Long userId, UserRole newRole) {
        UserProfile profile = findByIdOrThrow(userId);

        // Gán role trên Keycloak (vd: ROLE_INVESTOR)
        keycloakAdminService.assignRole(
                profile.getKeycloakUserId().toString(), "ROLE_" + newRole.name());

        // Cập nhật role trong DB
        profile.setRole(newRole);
        userProfileRepository.save(profile);

        log.info("Admin đã gán role {} cho userId={}, email={}",
                newRole, userId, PiiMasker.maskEmail(profile.getEmail()));
    }

    // ── Helper ──────────────────────────────────────────────────────

    /**
     * Kiểm tra hồ sơ đã đầy đủ chưa: fullName + CCCD (idNumberHash) + phone (phoneHash).
     */
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
