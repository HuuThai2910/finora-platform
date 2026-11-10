package com.finora.user.service.impl;

import com.finora.common.exception.ResourceNotFoundException;
import com.finora.common.util.PiiMasker;
import com.finora.user.domain.entity.UserProfile;
import com.finora.user.dto.response.UserFullNameResponse;
import com.finora.user.dto.response.UserIdMatchResponse;
import com.finora.user.dto.response.UserIdentityResponse;
import com.finora.user.repository.UserFullNameRow;
import com.finora.user.repository.UserProfileRepository;
import com.finora.user.repository.UserProfileSpecifications;
import com.finora.user.service.UserIdentityService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
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

    /** Chuỗi tìm theo tên ngắn hơn thế này khớp gần hết người dùng nên không tra. */
    static final int MIN_NAME_QUERY = 2;

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

    @Override
    public List<UserFullNameResponse> fullNamesOf(Collection<String> userIds) {
        // Tách hai dạng ID rồi đọc mỗi dạng một câu IN; trùng lặp và rỗng bỏ ngay từ đầu.
        Set<String> requested = new LinkedHashSet<>();
        List<Long> profileIds = new ArrayList<>();
        List<UUID> keycloakIds = new ArrayList<>();
        for (String userId : userIds == null ? List.<String>of() : userIds) {
            if (userId == null || userId.isBlank() || !requested.add(userId.trim())) {
                continue;
            }
            String value = userId.trim();
            if (value.chars().allMatch(Character::isDigit)) {
                parseProfileId(value).ifPresent(profileIds::add);
            } else {
                parseKeycloakId(value).ifPresent(keycloakIds::add);
            }
        }
        Map<String, String> names = new HashMap<>();
        if (!profileIds.isEmpty()) {
            for (UserFullNameRow row : userProfileRepository.findFullNamesByIdIn(profileIds)) {
                names.put(String.valueOf(row.id()), row.fullName());
            }
        }
        if (!keycloakIds.isEmpty()) {
            for (UserFullNameRow row : userProfileRepository.findFullNamesByKeycloakUserIdIn(keycloakIds)) {
                names.put(row.keycloakUserId().toString(), row.fullName());
            }
        }
        log.info("Cấp họ tên theo lô cho service nội bộ: requested={}, found={}", requested.size(), names.size());
        // Ghép theo dạng chuẩn (số bỏ số 0 đầu, UUID viết thường) nhưng trả lại đúng chuỗi bên gọi đã gửi.
        List<UserFullNameResponse> result = new ArrayList<>();
        for (String userId : requested) {
            String key = lookupKey(userId);
            if (key != null && names.containsKey(key)) {
                result.add(new UserFullNameResponse(userId, names.get(key)));
            }
        }
        return result;
    }

    @Override
    public List<UserIdMatchResponse> userIdsByName(String query, int limit) {
        if (query == null || query.isBlank() || limit <= 0) {
            return List.of();
        }
        String normalized = UserProfileSpecifications.normalizeSearchText(query);
        if (normalized.length() < MIN_NAME_QUERY) {
            return List.of();
        }
        List<UserIdMatchResponse> result = userProfileRepository
                .findFullNamesByNameContaining(normalized, PageRequest.of(0, limit)).stream()
                .map(row -> new UserIdMatchResponse(String.valueOf(row.id()),
                        row.keycloakUserId() == null ? null : row.keycloakUserId().toString()))
                .toList();
        // Không ghi chuỗi tìm (là họ tên) ra log.
        log.info("Tra ID người dùng theo họ tên cho service nội bộ: found={}", result.size());
        return result;
    }

    private static String lookupKey(String userId) {
        if (userId.chars().allMatch(Character::isDigit)) {
            return parseProfileId(userId).map(String::valueOf).orElse(null);
        }
        return parseKeycloakId(userId).map(UUID::toString).orElse(null);
    }

    private static Optional<Long> parseProfileId(String value) {
        try {
            return Optional.of(Long.parseLong(value));
        } catch (NumberFormatException e) {
            // Chuỗi số vượt giới hạn Long: không thể là ID hồ sơ.
            return Optional.empty();
        }
    }

    private static Optional<UUID> parseKeycloakId(String value) {
        try {
            return Optional.of(UUID.fromString(value));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
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
