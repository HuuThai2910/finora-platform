package com.finora.user.service;

import com.finora.user.dto.request.EkycVerifyRequest;
import com.finora.user.dto.response.EkycResultResponse;

import java.time.ZoneId;
import java.util.Map;
import java.util.UUID;

/**
 * Bước quét thẻ và bước xác nhận của eKYC (EKYC-002 mục 3.2, 3.5).
 */
public interface EkycVerificationService {

    /** Ngày hết hạn và tuổi tính theo lịch Việt Nam, không theo UTC của server. */
    ZoneId VIETNAM = ZoneId.of("Asia/Ho_Chi_Minh");

    EkycResultResponse verify(UUID keycloakUserId, EkycVerifyRequest request);

    EkycResultResponse confirm(UUID keycloakUserId, Map<String, String> edits);
}
