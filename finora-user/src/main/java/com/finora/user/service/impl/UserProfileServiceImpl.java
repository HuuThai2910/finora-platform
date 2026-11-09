package com.finora.user.service.impl;

import com.finora.common.dto.PageResponse;
import com.finora.common.enums.user.EkycStatus;
import com.finora.common.enums.user.UserRole;
import com.finora.common.exception.BusinessException;
import com.finora.common.exception.ResourceNotFoundException;
import com.finora.common.util.CryptoUtils;
import com.finora.common.util.PiiMasker;
import com.finora.user.config.CryptoProperties;
import com.finora.user.domain.entity.UserProfile;
import com.finora.user.domain.enums.AccountScope;
import com.finora.user.dto.request.UserListFilter;
import com.finora.user.dto.request.UserSearchCriteria;
import com.finora.user.dto.response.UserProfileResponse;
import com.finora.user.dto.response.UserStatsResponse;
import com.finora.user.mapper.UserProfileMapper;
import com.finora.user.repository.UserProfileRepository;
import com.finora.user.repository.UserProfileSpecifications;
import com.finora.user.service.KeycloakAdminService;
import com.finora.user.service.StaffRoleService;
import com.finora.user.service.UserProfileService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
    private final StaffRoleService staffRoleService;
    private final CryptoProperties cryptoProperties;
    private final Clock clock;

    /** Múi giờ nghiệp vụ: khoảng ngày của bảng lọc và mốc tính tuổi theo giờ Việt Nam. */
    static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Ho_Chi_Minh");

    /** Giới hạn chuỗi tìm, đủ cho họ tên hoặc email dài. */
    static final int MAX_SEARCH_LENGTH = 100;

    // ── Hồ sơ cá nhân ──────────────────────────────────────────────

    @Override
    public UserProfileResponse getMyProfile(UUID keycloakUserId, Collection<String> authorities) {
        UserProfile profile = findByKeycloakUserIdOrThrow(keycloakUserId);
        UserProfileResponse response = withStaffRole(userProfileMapper.toResponse(profile), profile);
        response.setPermissions(StaffRoleService.permissionsOf(authorities));
        return response;
    }

    // ── Quản trị (Admin) ────────────────────────────────────────────

    @Override
    public PageResponse<UserProfileResponse> searchUsers(UserSearchCriteria criteria, Pageable pageable) {
        UserRole roleFilter = parseRole(criteria.role());
        AccountScope scope = parseScope(criteria.scope());
        EkycStatus ekycFilter = parseEkycStatus(criteria.ekycStatus());
        String staffRoleFilter = isNoFilter(criteria.staffRoleCode()) ? null : criteria.staffRoleCode().trim();
        // Vai trò nằm ngoài nhóm đang xem thì chắc chắn rỗng: báo lỗi để client không hiện "không có ai" sai nghĩa.
        if (scope != null && roleFilter != null && !scope.contains(roleFilter)) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "ROLE_OUT_OF_SCOPE",
                    "Vai trò " + roleFilter + " không thuộc nhóm tài khoản " + scope);
        }
        if (staffRoleFilter != null && scope == AccountScope.CUSTOMER) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "STAFF_ROLE_NOT_ALLOWED",
                    "Chỉ lọc được vai trò nhân viên khi xem tài khoản nhân viên");
        }
        if (staffRoleFilter != null) {
            // Chỉ tài khoản STAFF mới mang mã vai trò nhân viên (ràng buộc ck_user_profiles_staff_role_code).
            if (roleFilter != null && roleFilter != UserRole.STAFF) {
                throw new BusinessException(HttpStatus.BAD_REQUEST, "STAFF_ROLE_NOT_ALLOWED",
                        "Chỉ lọc được vai trò nhân viên khi xem tài khoản nhân viên");
            }
            roleFilter = UserRole.STAFF;
        }

        String search = null;
        List<String> identifierHashes = List.of();
        if (criteria.q() != null && !criteria.q().isBlank()) {
            String raw = criteria.q().trim();
            if (raw.length() > MAX_SEARCH_LENGTH) {
                throw new BusinessException(HttpStatus.BAD_REQUEST, "SEARCH_TOO_LONG",
                        "Chuỗi tìm tối đa " + MAX_SEARCH_LENGTH + " ký tự");
            }
            search = UserProfileSpecifications.normalizeSearchText(raw);
            identifierHashes = identifierCandidates(raw).stream()
                    .map(value -> CryptoUtils.hmacSha256(value, cryptoProperties.getHmacSecret()))
                    .toList();
        }

        Pageable sorted = PageRequest.of(pageable.getPageNumber(), Math.min(Math.max(pageable.getPageSize(), 1), 100),
                Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("id")));
        UserListFilter advanced = criteria.filter().validate();
        Specification<UserProfile> specification = UserProfileSpecifications.matching(
                roleFilter, scope == null ? null : scope.roles(), ekycFilter, staffRoleFilter, search, identifierHashes);
        if (!advanced.isEmpty()) {
            specification = specification.and(UserProfileSpecifications.withFilter(
                    advanced, LocalDate.now(clock.withZone(BUSINESS_ZONE)), BUSINESS_ZONE));
        }
        Page<UserProfile> page = userProfileRepository.findAll(specification, sorted);

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

    /**
     * Các dạng viết có thể của số điện thoại / CCCD trong chuỗi tìm. Số được băm đúng như lúc đăng ký (chuỗi đã trim),
     * nên ngoài chuỗi gốc còn thử bản chỉ có chữ số và hai cách viết đầu số Việt Nam {@code 0…} / {@code +84…}.
     * Chuỗi không đủ 9–12 chữ số thì không phải số điện thoại hay CCCD, khỏi băm.
     */
    static Set<String> identifierCandidates(String raw) {
        String digits = raw.chars().filter(Character::isDigit)
                .collect(StringBuilder::new, StringBuilder::appendCodePoint, StringBuilder::append).toString();
        Set<String> candidates = new LinkedHashSet<>();
        if (digits.length() < 9 || digits.length() > 12) {
            return candidates;
        }
        candidates.add(raw);
        candidates.add(digits);
        if (digits.startsWith("84") && digits.length() == 11) {
            String local = digits.substring(2);
            candidates.add("0" + local);
            candidates.add("+84" + local);
        } else if (digits.startsWith("0") && digits.length() == 10) {
            String local = digits.substring(1);
            candidates.add("+84" + local);
            candidates.add("84" + local);
        }
        return candidates;
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

    /** Nhóm tài khoản {@code STAFF}/{@code CUSTOMER}; rỗng hoặc "ALL" là không giới hạn. */
    private AccountScope parseScope(String scope) {
        if (isNoFilter(scope)) return null;
        try {
            return AccountScope.valueOf(scope.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "SCOPE_INVALID",
                    "Nhóm tài khoản không hợp lệ: " + scope);
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
        UserProfileResponse response = withStaffRole(userProfileMapper.toResponse(profile), profile);
        Boolean enabled = keycloakAdminService.isUserEnabled(profile.getKeycloakUserId().toString());
        response.setLocked(enabled == null ? null : !enabled);
        return response;
    }

    /** Gắn mã + tên vai trò nhân viên; tên đọc từ bộ nhớ đệm của {@link StaffRoleService}. */
    private UserProfileResponse withStaffRole(UserProfileResponse response, UserProfile profile) {
        String code = profile.getRole() == UserRole.STAFF ? profile.getStaffRoleCode() : null;
        response.setStaffRoleCode(code);
        if (code == null) {
            return response;
        }
        String name = staffRoleService.nameOf(code);
        response.setStaffRoleName(name);
        response.setStaffRole(new UserProfileResponse.StaffRoleRef(code, name));
        return response;
    }

    @Override
    @Transactional
    public void lockUser(Long userId, String reason, UUID currentKeycloakId) {
        if (reason == null || reason.isBlank()) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "LOCK_REASON_REQUIRED", "Vui lòng nhập lý do khóa");
        }
        UserProfile profile = findByIdOrThrow(userId);
        preventSelfAction(profile, currentKeycloakId, "Không thể tự khóa tài khoản của chính mình");
        String keycloakUserId = profile.getKeycloakUserId().toString();
        keycloakAdminService.disableUser(keycloakUserId);

        profile.markLocked(reason.trim(), currentKeycloakId, clock.instant());
        userProfileRepository.save(profile);

        // Tắt tài khoản chỉ chặn đăng nhập mới; đăng xuất để thu hồi refresh token của mọi phiên đang mở
        // (app và web không làm mới phiên được nữa). Lỗi đăng xuất chỉ ghi log, không làm hỏng lệnh khóa.
        keycloakAdminService.logoutUser(keycloakUserId);
        log.info("Admin đã khóa tài khoản userId={}, email={}",
                userId, PiiMasker.maskEmail(profile.getEmail()));
    }

    @Override
    @Transactional
    public void unlockUser(Long userId) {
        UserProfile profile = findByIdOrThrow(userId);
        keycloakAdminService.enableUser(profile.getKeycloakUserId().toString());
        profile.clearLock();
        userProfileRepository.save(profile);
        log.info("Admin đã mở khóa tài khoản userId={}, email={}",
                userId, PiiMasker.maskEmail(profile.getEmail()));
    }

    @Override
    @Transactional
    public void assignRole(Long userId, String role, String staffRoleCode, UUID currentKeycloakId) {
        if (role == null || role.isBlank()) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "Vai trò không được để trống");
        }

        UserRole newRole;
        try {
            newRole = UserRole.valueOf(role.toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "Vai trò không hợp lệ: " + role);
        }

        String staffCode = staffRoleCode == null || staffRoleCode.isBlank() ? null : staffRoleCode.trim();
        if (newRole == UserRole.STAFF && staffCode == null) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "STAFF_ROLE_REQUIRED",
                    "Tài khoản nhân viên phải chọn một vai trò nhân viên");
        }
        if (newRole != UserRole.STAFF && staffCode != null) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "STAFF_ROLE_NOT_ALLOWED",
                    "Chỉ tài khoản nhân viên mới gán được vai trò nhân viên");
        }

        // Người vay/nhà đầu tư do khách tự chọn lúc đăng ký; tài khoản nội bộ không được hạ thành khách hàng.
        if (!AccountScope.STAFF.contains(newRole)) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "ROLE_NOT_ASSIGNABLE",
                    "Chỉ đổi được giữa Quản trị viên và Nhân viên");
        }

        UserProfile profile = findByIdOrThrow(userId);
        // Khách hàng không bao giờ thành nhân viên: tài khoản nhân viên phải tạo mới bằng thông tin riêng.
        if (!AccountScope.STAFF.contains(profile.getRole())) {
            throw new BusinessException(HttpStatus.CONFLICT, "CUSTOMER_ROLE_LOCKED",
                    "Không đổi vai trò được cho tài khoản khách hàng; hãy tạo tài khoản nhân viên mới");
        }
        preventSelfAction(profile, currentKeycloakId, "Không thể tự đổi vai trò của chính mình");

        // Vai trò nhân viên phải tồn tại thật trên Keycloak (404 STAFF_ROLE_NOT_FOUND nếu không).
        List<String> realmRoles = new ArrayList<>();
        realmRoles.add("ROLE_" + newRole.name());
        if (newRole == UserRole.STAFF) {
            realmRoles.add(staffRoleService.requireStaffRole(staffCode).code());
        }

        String keycloakUserId = profile.getKeycloakUserId().toString();
        keycloakAdminService.replaceRealmRoles(keycloakUserId, realmRoles);

        profile.setRole(newRole);
        profile.setStaffRoleCode(staffCode);
        userProfileRepository.save(profile);

        // Token đang cầm vẫn mang quyền cũ tới khi hết hạn — buộc đăng nhập lại.
        keycloakAdminService.logoutUser(keycloakUserId);

        log.info("Admin đã gán role {} (vai trò nhân viên {}) cho userId={}, email={}",
                newRole, staffCode, userId, PiiMasker.maskEmail(profile.getEmail()));
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
