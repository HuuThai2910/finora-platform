package com.finora.user.dto.response;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Chi tiết một lượt eKYC cho người duyệt: các field của {@link EkycAttemptSummaryResponse} cộng bản nháp
 * đã giải mã (cùng khuôn {@link EkycResultResponse.DraftView}), mã lý do liveness, phiên bản điều khoản
 * đã đồng ý, danh sách bằng chứng và quyết định duyệt. Mỗi lần mở chi tiết được ghi nhật ký truy cập.
 *
 * @param faceMatchZone   {@code HIGH | GRAY | LOW}, suy từ mã lý do liveness; {@code null} khi chưa so mặt
 * @param draft           {@code null} khi lượt tự xác minh (dữ liệu đã nằm ở hồ sơ) hoặc bản nháp đã bị xoá
 * @param livenessReasons mã lý do liveness dạng chuỗi, như {@code reasons}
 * @param reviewedAt      thời điểm có quyết định cuối (duyệt, từ chối, tự xác minh hoặc bỏ dở)
 * @param reviewerEmail   email người duyệt, tra từ hồ sơ có {@code keycloak_user_id = reviewerKeycloakId};
 *                        {@code null} khi chưa có người duyệt hoặc tài khoản admin không có hồ sơ FINORA
 *                        (tạo thẳng trên Keycloak) — khi đó chỉ còn {@code reviewerKeycloakId}
 * @param reviewerName    họ tên người duyệt theo cùng cách tra; {@code null} nếu hồ sơ chưa có họ tên
 */
public record EkycAttemptDetailResponse(
        UUID attemptId,
        Long userProfileId,
        String email,
        String status,
        String cardType,
        String documentDecision,
        String livenessResult,
        Double faceMatchScore,
        String faceMatchZone,
        List<String> reasons,
        Instant createdAt,
        Instant submittedAt,
        EkycResultResponse.DraftView draft,
        List<String> livenessReasons,
        String consentVersion,
        List<EvidenceItem> evidence,
        String reviewNote,
        Instant reviewedAt,
        UUID reviewerKeycloakId,
        String reviewerEmail,
        String reviewerName
) {

    /**
     * Metadata một object bằng chứng; nội dung tải qua endpoint stream riêng.
     *
     * @param evidenceId ID số trong bảng {@code ekyc_evidence}, dùng ở đường dẫn tải bằng chứng
     */
    public record EvidenceItem(Long evidenceId, String kind, String label, String contentType,
                               long sizeBytes, boolean deleted) {
    }
}
