package com.finora.user.service.impl;

import com.finora.common.enums.user.UserRole;
import com.finora.common.exception.BusinessException;
import com.finora.common.util.CryptoUtils;
import com.finora.common.util.PiiMasker;
import com.finora.user.config.CryptoProperties;
import com.finora.user.domain.entity.UserProfile;
import com.finora.user.domain.enums.AccountScope;
import com.finora.user.domain.model.StaffRoleDefinition;
import com.finora.user.dto.request.CreateStaffAccountRequest;
import com.finora.user.dto.response.StaffAccountCreatedResponse;
import com.finora.user.repository.UserProfileRepository;
import com.finora.user.service.KeycloakAdminService;
import com.finora.user.service.NotificationService;
import com.finora.user.service.StaffAccountService;
import com.finora.user.service.StaffRoleService;
import com.finora.user.util.TemporaryPasswordGenerator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Locale;
import java.util.UUID;

/**
 * Triển khai tạo tài khoản nội bộ — cùng cách tạo user Keycloak và hồ sơ như bước xác thực đăng ký
 * ({@code AuthServiceImpl#verifyRegistration}), nhưng không qua OTP, không cấp token và không phát sự kiện nào: luồng
 * đăng ký khách hàng cũng không tạo ví ở finora-user (ví do Payment tạo khi khách dùng tới), nên nhân viên không có ví.
 * <p>
 * Thứ tự: kiểm hết đầu vào (vai trò nhân viên phải tồn tại trên Keycloak, email/số điện thoại chưa dùng) rồi mới tạo
 * user Keycloak; mọi lỗi sau khi đã tạo user đều xoá user đó để email không bị kẹt 409 vĩnh viễn.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class StaffAccountServiceImpl implements StaffAccountService {

    private static final String ADMIN_LABEL = "Quản trị viên";
    private static final String STAFF_LABEL = "Nhân viên";

    private final KeycloakAdminService keycloakAdminService;
    private final UserProfileRepository userProfileRepository;
    private final StaffRoleService staffRoleService;
    private final NotificationService notificationService;
    private final CryptoProperties cryptoProperties;

    @Override
    @Transactional
    public StaffAccountCreatedResponse createStaffAccount(CreateStaffAccountRequest request) {
        UserRole role = parseInternalRole(request.getRole());
        String staffCode = blankToNull(request.getStaffRoleCode());
        if (role == UserRole.STAFF && staffCode == null) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "STAFF_ROLE_REQUIRED",
                    "Tài khoản nhân viên phải chọn một vai trò nhân viên");
        }
        if (role != UserRole.STAFF && staffCode != null) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "STAFF_ROLE_NOT_ALLOWED",
                    "Chỉ tài khoản nhân viên mới gán được vai trò nhân viên");
        }
        // 404 STAFF_ROLE_NOT_FOUND nếu vai trò không còn trên Keycloak — kiểm trước khi tạo gì.
        StaffRoleDefinition staffRole = role == UserRole.STAFF ? staffRoleService.requireStaffRole(staffCode) : null;

        // Keycloak lưu username chữ thường; lưu hồ sơ cùng dạng để tra cứu theo email khớp nhau.
        String email = request.getEmail().trim().toLowerCase(Locale.ROOT);
        String fullName = request.getFullName().trim();
        String phone = blankToNull(request.getPhone());
        String phoneHash = phone == null ? null : CryptoUtils.hmacSha256(phone, cryptoProperties.getHmacSecret());

        if (userProfileRepository.existsByEmail(email)) {
            throw new BusinessException(HttpStatus.CONFLICT, "EMAIL_TAKEN", "Email đã được sử dụng");
        }
        if (phoneHash != null && userProfileRepository.existsByPhoneHash(phoneHash)) {
            throw new BusinessException(HttpStatus.CONFLICT, "PHONE_TAKEN",
                    "Số điện thoại này đã được đăng ký trong hệ thống");
        }

        String temporaryPassword = TemporaryPasswordGenerator.generate();
        // createUser gán sẵn realm role ROLE_STAFF/ROLE_ADMIN; trùng email trên Keycloak ném 409.
        String keycloakUserId = keycloakAdminService.createUser(email, temporaryPassword, fullName, role);

        UserProfile profile;
        try {
            if (staffRole != null) {
                // Realm role STAFF_* là composite gom quyền ops:* (client role finora-ops) của vai trò nhân viên.
                keycloakAdminService.assignRole(keycloakUserId, staffRole.code());
            }
            profile = userProfileRepository.save(UserProfile.builder()
                    .keycloakUserId(UUID.fromString(keycloakUserId))
                    .email(email)
                    .fullName(fullName)
                    .phoneHash(phoneHash)
                    .phoneEncrypted(phone)
                    .role(role)
                    .staffRoleCode(staffRole != null ? staffRole.code() : null)
                    .mustChangePassword(true)
                    .build());
            // Protocol mapper "user-id" phát claim user_id từ attribute này; thiếu thì service khác từ chối token.
            keycloakAdminService.setUserIdAttribute(keycloakUserId, profile.getId());
        } catch (Exception e) {
            // Giao dịch DB rollback theo exception; user Keycloak thì phải xoá tay (best-effort).
            log.error("Không hoàn tất tạo tài khoản nhân viên sau khi tạo Keycloak user — rollback: email={}",
                    PiiMasker.maskEmail(email), e);
            keycloakAdminService.deleteUser(keycloakUserId);
            throw new BusinessException(HttpStatus.INTERNAL_SERVER_ERROR, "STAFF_ACCOUNT_CREATE_FAILED",
                    "Không thể tạo tài khoản nhân viên, vui lòng thử lại sau");
        }

        String roleName = staffRole != null ? STAFF_LABEL + " (" + staffRole.name() + ")" : ADMIN_LABEL;
        boolean emailSent = notificationService.sendStaffAccountEmail(
                profile.getId(), email, fullName, roleName, temporaryPassword);

        log.info("Đã tạo tài khoản nội bộ: userId={}, role={}, staffRole={}, email={}",
                profile.getId(), role, staffRole != null ? staffRole.code() : null, PiiMasker.maskEmail(email));

        return new StaffAccountCreatedResponse(
                profile.getId(),
                email,
                fullName,
                role,
                staffRole != null ? staffRole.code() : null,
                staffRole != null ? staffRole.name() : null,
                temporaryPassword,
                emailSent);
    }

    /** Chỉ STAFF hoặc ADMIN; người vay/nhà đầu tư chỉ tự đăng ký trên app. */
    private static UserRole parseInternalRole(String raw) {
        UserRole role;
        try {
            role = UserRole.valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException | NullPointerException e) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "Vai trò không hợp lệ: " + raw);
        }
        if (!AccountScope.STAFF.contains(role)) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "ROLE_NOT_ASSIGNABLE",
                    "Chỉ tạo được tài khoản Quản trị viên hoặc Nhân viên");
        }
        return role;
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
