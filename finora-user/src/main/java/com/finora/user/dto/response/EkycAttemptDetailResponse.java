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
 * @param aiUnavailable   hồ sơ chuyển duyệt tay vì finora-ai gặp sự cố — nhân viên phải đọc thẻ và so mặt
 * @param requiredIdentityFields trường nhân viên phải nhập khi duyệt (tên trường của bản nháp, thêm {@code cardType});
 *                        chỉ có với hồ sơ AI gặp sự cố hoặc người dùng báo sai đang chờ duyệt, còn lại là danh sách rỗng
 * @param userReportedIncorrect người dùng chụp lại nhiều lần mà vẫn báo thông tin đọc từ thẻ chưa đúng (mã
 *                        {@code USER_REPORTED_INCORRECT}): nhân viên đối chiếu ảnh thẻ và được sửa mọi trường
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
        String reviewerName,
        boolean aiUnavailable,
        List<String> requiredIdentityFields,
        boolean userReportedIncorrect
) {

    public EkycAttemptDetailResponse {
        requiredIdentityFields = requiredIdentityFields == null ? List.of() : List.copyOf(requiredIdentityFields);
    }

    /**
     * Metadata một object bằng chứng; nội dung tải qua endpoint stream riêng.
     *
     * @param evidenceId ID số trong bảng {@code ekyc_evidence}, dùng ở đường dẫn tải bằng chứng
     */
    public record EvidenceItem(Long evidenceId, String kind, String label, String contentType,
                               long sizeBytes, boolean deleted) {
    }
}
