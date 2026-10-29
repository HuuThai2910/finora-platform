package com.finora.user.service.impl;

import com.finora.common.exception.ResourceNotFoundException;
import com.finora.user.domain.UserProfile;
import com.finora.user.dto.response.UserIdentityResponse;
import com.finora.user.repository.UserProfileRepository;
import com.finora.user.service.UserIdentityService;
import com.finora.user.support.PiiMasker;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;
import java.util.UUID;

/**
 * Tra họ tên và CCCD đã che của một người dùng cho service nội bộ (giấy xác nhận chuyển nhượng Note của Investment).
 *
 * <p>Investment lưu investorId/buyer_id/seller_id bằng {@code SecurityUtils.getCurrentUserId()}: claim {@code user_id}
 * (= {@code user_profiles.id}) khi có, không thì rơi về {@code sub} (Keycloak UUID) với tài khoản cũ chưa được gắn
 * attribute. Vì vậy nhận cả hai dạng: chuỗi số tra theo ID hồ sơ, chuỗi UUID tra theo Keycloak user ID.</p>
 *
 * <p>Số CCCD được {@code CryptoConverter} giải mã khi JPA đọc; service chỉ trả bản đã che và không ghi số CCCD ra log.</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class UserIdentityServiceImpl implements UserIdentityService {

    private final UserProfileRepository userProfileRepository;

    @Override
    public UserIdentityResponse identityOf(String userId) {
        UserProfile profile = findProfile(userId)
                .orElseThrow(() -> new ResourceNotFoundException("Hồ sơ người dùng", "userId", userId));
        String maskedIdNumber = PiiMasker.maskIdNumberKeepLastFour(profile.getIdNumberEncrypted());
        log.info("Cấp danh tính đã che cho service nội bộ: userId={}, hasIdNumber={}",
                profile.getId(), maskedIdNumber != null);
        return new UserIdentityResponse(userId, profile.getFullName(), maskedIdNumber);
    }

    private Optional<UserProfile> findProfile(String userId) {
        if (userId == null || userId.isBlank()) {
            return Optional.empty();
        }
        String value = userId.trim();
        if (value.chars().allMatch(Character::isDigit)) {
            try {
                return userProfileRepository.findById(Long.parseLong(value));
            } catch (NumberFormatException e) {
                // Chuỗi số vượt giới hạn Long: không thể là ID hồ sơ.
                return Optional.empty();
            }
        }
        try {
            return userProfileRepository.findByKeycloakUserId(UUID.fromString(value));
        } catch (IllegalArgumentException e) {
            // Không phải ID hồ sơ cũng không phải UUID: coi như không tồn tại, trả 404 thay vì 400.
            return Optional.empty();
        }
    }
}
