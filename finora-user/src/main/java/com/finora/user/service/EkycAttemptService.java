package com.finora.user.service;

import com.finora.user.domain.EkycAttempt;
import com.finora.user.domain.EkycIdentityDraft;
import com.finora.user.domain.EkycLivenessResult;
import com.finora.user.domain.UserProfile;
import com.finora.user.service.EkycEvidenceService.StoredEvidence;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Set;

/**
 * Các bước ghi database của lượt eKYC, dùng chung cho quét thẻ, liveness, xác nhận, duyệt tay và quét dọn.
 */
public interface EkycAttemptService {

    /**
     * Huỷ lượt đang mở cũ (quét lại) và mở lượt mới cho bản nháp vừa đọc.
     *
     * @return ID lượt mới và object key còn sống của lượt cũ — xoá trên kho sau khi commit
     */
    StartedAttempt start(Long userProfileId, Long consentId, EkycIdentityDraft draft, Instant now);

    void recordEvidence(Long attemptId, Collection<StoredEvidence> items, Instant now);

    /** Upload lỗi hoặc không còn dùng được: bỏ lượt; object (nếu có) do worker quét dọn xoá tiếp. */
    void abandon(Long attemptId, Instant now);

    int markDeleted(Collection<String> objectKeys, Instant now);

    /**
     * Ghi kết quả một lần gửi video: bằng chứng mới luôn được lưu; kết luận chỉ ghi khi AI có kết luận
     * ({@code result != null}) — video phải quay lại không ghi đè kết luận trước.
     */
    void recordLiveness(EkycAttempt attempt, EkycLivenessResult result, List<String> reasons,
                        Double faceMatchScore, String thresholdsVersion,
                        Collection<StoredEvidence> evidence, Instant now);

    /**
     * Xác minh tự động: ghi định danh vào hồ sơ, hồ sơ + lượt VERIFIED, bằng chứng sang {@code final},
     * thêm {@code result.json} và task ánh xạ CIC — cùng một transaction.
     */
    void completeVerified(UserProfile profile, EkycAttempt attempt, EkycIdentityDraft draft,
                          StoredEvidence result, Instant now);

    /**
     * Gửi duyệt tay: hồ sơ + lượt MANUAL_REVIEW, bản nháp (đã bỏ embedding) mã hoá vào lượt để nhân viên
     * duyệt sau này. Trường định danh chưa được ghi vào hồ sơ.
     */
    void submitForReview(UserProfile profile, EkycAttempt attempt, EkycIdentityDraft draft,
                         StoredEvidence result, Instant now);

    /** Bằng chứng còn sống của lượt chuyển sang giai đoạn giữ theo hạn lưu. */
    void finalizeEvidence(Long attemptId, Instant now);

    /** Mỗi hồ sơ một task ánh xạ CIC; xác minh lại sau khi rút đồng ý không tạo task thứ hai. */
    void ensureCicTask(Long userProfileId, Instant now);

    String draftJson(EkycIdentityDraft draft);

    /** Đọc bản nháp đã lưu ở lượt; {@code null} nếu không có hoặc không đọc được. */
    EkycIdentityDraft readDraft(String json);

    String toJson(List<String> codes);

    List<String> readCodes(String json);

    /**
     * Vùng so khớp khuôn mặt suy từ mã lý do đã lưu: {@code FACE_MATCH_HIGH} → HIGH,
     * {@code FACE_MATCH_GRAY} → GRAY, {@code FACE_MISMATCH} → LOW; chưa so mặt → {@code null}.
     */
    static String faceMatchZone(Collection<String> livenessReasons) {
        Set<String> codes = livenessReasons == null ? Set.of() : Set.copyOf(livenessReasons);
        if (codes.contains("FACE_MISMATCH")) {
            return "LOW";
        }
        if (codes.contains("FACE_MATCH_GRAY")) {
            return "GRAY";
        }
        if (codes.contains("FACE_MATCH_HIGH")) {
            return "HIGH";
        }
        return null;
    }

    /** Mã lý do tương ứng với vùng AI trả, để lưu kèm khi AI không tự đưa mã đó vào {@code reasons}. */
    static String zoneReasonCode(String zone) {
        if (zone == null) {
            return null;
        }
        return switch (zone) {
            case "HIGH" -> "FACE_MATCH_HIGH";
            case "GRAY" -> "FACE_MATCH_GRAY";
            case "LOW" -> "FACE_MISMATCH";
            default -> null;
        };
    }

    /** Lượt vừa mở và object key của lượt cũ cần xoá. */
    record StartedAttempt(Long attemptId, List<String> staleObjectKeys) {
    }
}
