package com.finora.user.service;

import com.finora.common.enums.user.EkycStatus;

import java.util.UUID;

/**
 * Ghi trạng thái eKYC lên attribute Keycloak {@code ekyc_status} (claim cùng tên trong token) để
 * Investment/Loan chặn thao tác khi chưa xác minh mà không phải gọi ngược finora-user.
 */
public interface EkycClaimPublisher {

    void publish(UUID keycloakUserId, EkycStatus status);
}
