package com.finora.user.service.impl;

import com.finora.common.enums.user.EkycStatus;
import com.finora.user.service.EkycClaimPublisher;
import com.finora.user.service.KeycloakAdminService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * Ghi trạng thái eKYC lên attribute Keycloak {@code ekyc_status} (claim cùng tên trong token) để
 * Investment/Loan chặn thao tác khi chưa xác minh mà không phải gọi ngược finora-user.
 * <p>
 * Chỉ gọi <b>sau</b> khi transaction DB đã commit: ghi trước mà DB rollback thì token mang claim
 * VERIFIED cho hồ sơ chưa xác minh. Keycloak lỗi không làm hỏng kết quả đã lưu — lần đăng nhập sau
 * {@code AuthServiceImpl} ghi bù theo trạng thái trong DB.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class EkycClaimPublisherImpl implements EkycClaimPublisher {

    private final KeycloakAdminService keycloakAdminService;

    @Override
    public void publish(UUID keycloakUserId, EkycStatus status) {
        try {
            keycloakAdminService.setEkycStatusAttribute(keycloakUserId.toString(), status.name());
        } catch (RuntimeException e) {
            log.warn("Chưa ghi được ekyc_status lên Keycloak, sẽ ghi bù khi đăng nhập lại: keycloakUserId={}",
                    keycloakUserId);
        }
    }
}
