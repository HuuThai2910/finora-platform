package com.finora.user.service;

import com.finora.user.domain.EkycConsent;
import com.finora.user.dto.request.EkycConsentAcceptRequest;
import com.finora.user.dto.response.EkycConsentResponse;

import java.util.Optional;
import java.util.UUID;

/**
 * Điều khoản xử lý dữ liệu eKYC: xem, đồng ý, rút lại (EKYC-002 mục 3.1; kiểm soát {@code LEGAL-DATA-01}).
 */
public interface EkycConsentService {

    EkycConsentResponse getConsent(UUID keycloakUserId);

    /** Đồng ý còn hiệu lực với điều khoản hiện hành, nếu có. */
    Optional<EkycConsent> findActiveConsent(Long userProfileId);

    /**
     * Ghi nhận đồng ý. Phiên bản hoặc hash lệch bản hiện hành → {@code 409 CONSENT_VERSION_STALE}: người
     * dùng đã đọc một nội dung khác nội dung server sẽ áp dụng. Gửi lại khi đã có đồng ý còn hiệu lực
     * trả bản ghi cũ (bấm hai lần không tạo hai bản ghi).
     */
    EkycConsentResponse accept(UUID keycloakUserId, EkycConsentAcceptRequest request);

    EkycConsentResponse withdraw(UUID keycloakUserId);
}
