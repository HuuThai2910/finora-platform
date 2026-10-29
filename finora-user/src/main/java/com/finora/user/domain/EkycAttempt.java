package com.finora.user.domain;

import com.finora.user.support.CryptoConverter;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.UUID;

/**
 * Một lượt xác minh eKYC — bảng {@code ekyc_attempts}. Mỗi lần quét thẻ đạt tạo một lượt; ảnh, video
 * và kết quả của lượt nằm ở {@link EkycEvidence}.
 * <p>
 * {@code publicId} là mã lượt đưa ra API và nằm trong object key; ID số chỉ dùng nội bộ để không lộ
 * số lượng hồ sơ. {@code @Version} chặn hai nhân viên duyệt cùng một lượt: người lưu sau nhận
 * xung đột thay vì ghi đè quyết định của người trước.
 * <p>
 * Mọi chuyển trạng thái đi qua các hàm bên dưới (whitelist); gán trạng thái tuỳ ý sẽ phá vòng đời
 * mô tả ở {@link EkycAttemptStatus}.
 */
@Entity
@Table(name = "ekyc_attempts")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class EkycAttempt {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "public_id", nullable = false, unique = true, updatable = false)
    private UUID publicId;

    @Column(name = "user_profile_id", nullable = false, updatable = false)
    private Long userProfileId;

    /** Bản ghi đồng ý đang hiệu lực lúc mở lượt — chứng minh lượt này được xử lý có đồng ý. */
    @Column(name = "consent_id", updatable = false)
    private Long consentId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private EkycAttemptStatus status;

    @Column(name = "card_type", length = 30)
    private String cardType;

    @Enumerated(EnumType.STRING)
    @Column(name = "document_decision", nullable = false, length = 20)
    private EkycDocumentDecision documentDecision;

    /** JSON mảng mã lý do/cảnh báo của bước đọc thẻ — chỉ mã, không có giá trị trường. */
    @Column(name = "document_reasons", columnDefinition = "TEXT")
    private String documentReasons;

    @Enumerated(EnumType.STRING)
    @Column(name = "liveness_result", length = 10)
    private EkycLivenessResult livenessResult;

    @Column(name = "liveness_reasons", columnDefinition = "TEXT")
    private String livenessReasons;

    @Column(name = "face_match_score")
    private Double faceMatchScore;

    /** Phiên bản ngưỡng AI đã dùng, dạng {@code <đọc thẻ>,<liveness>} để tái lập được quyết định. */
    @Column(name = "thresholds_version", length = 80)
    private String thresholdsVersion;

    /**
     * Bản nháp gửi duyệt tay (JSON, không có embedding khuôn mặt). Mã hoá AES-GCM tự động qua
     * {@link CryptoConverter} vì chứa số định danh, họ tên, địa chỉ.
     */
    @Convert(converter = CryptoConverter.class)
    @Column(name = "draft_encrypted", columnDefinition = "TEXT")
    private String draftEncrypted;

    @Column(name = "submitted_at")
    private Instant submittedAt;

    @Column(name = "decided_at")
    private Instant decidedAt;

    @Column(name = "reviewer_keycloak_id")
    private UUID reviewerKeycloakId;

    @Column(name = "review_note", length = 1000)
    private String reviewNote;

    @Version
    @Column(nullable = false)
    private Long version;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    /** Mở lượt mới sau khi bước đọc thẻ đạt mọi luật nghiệp vụ. */
    public static EkycAttempt start(UUID publicId, Long userProfileId, Long consentId, String cardType,
                                    EkycDocumentDecision documentDecision, String documentReasonsJson,
                                    String thresholdsVersion, Instant now) {
        if (publicId == null || userProfileId == null || documentDecision == null || now == null) {
            throw new IllegalArgumentException("Thiếu dữ liệu bắt buộc khi mở lượt eKYC");
        }
        EkycAttempt attempt = new EkycAttempt();
        attempt.publicId = publicId;
        attempt.userProfileId = userProfileId;
        attempt.consentId = consentId;
        attempt.status = EkycAttemptStatus.IN_PROGRESS;
        attempt.cardType = cardType;
        attempt.documentDecision = documentDecision;
        attempt.documentReasons = documentReasonsJson;
        attempt.thresholdsVersion = truncate(thresholdsVersion, 80);
        attempt.createdAt = now;
        attempt.updatedAt = now;
        return attempt;
    }

    public boolean isInProgress() {
        return status == EkycAttemptStatus.IN_PROGRESS;
    }

    /** Lượt đang làm dở bị bỏ (quét lại, quá hạn, upload lỗi). Lượt đã kết thúc giữ nguyên. */
    public void abandon(Instant now) {
        if (status == EkycAttemptStatus.IN_PROGRESS) {
            status = EkycAttemptStatus.ABANDONED;
            decidedAt = now;
            draftEncrypted = null;
            updatedAt = now;
        }
    }

    /**
     * Người dùng rút lại đồng ý: lượt còn mở (kể cả đang chờ duyệt) bị bỏ vì bằng chứng sắp bị xoá;
     * lượt đã có quyết định giữ nguyên trạng thái nhưng mất bản nháp.
     */
    public void withdraw(Instant now) {
        if (status == EkycAttemptStatus.IN_PROGRESS || status == EkycAttemptStatus.MANUAL_REVIEW) {
            status = EkycAttemptStatus.ABANDONED;
            decidedAt = now;
        }
        draftEncrypted = null;
        updatedAt = now;
    }

    /** Ghi kết quả liveness PASS/REVIEW/FAIL; chỉ khi lượt còn mở. */
    public void recordLiveness(EkycLivenessResult result, String reasonsJson, Double score,
                               String thresholdsVersion, Instant now) {
        requireStatus(EkycAttemptStatus.IN_PROGRESS);
        this.livenessResult = result;
        this.livenessReasons = reasonsJson;
        this.faceMatchScore = score;
        this.thresholdsVersion = truncate(thresholdsVersion, 80);
        this.updatedAt = now;
    }

    /** Giấy tờ AUTO_OK + liveness PASS và người dùng đã xác nhận. */
    public void markVerified(Instant now) {
        requireStatus(EkycAttemptStatus.IN_PROGRESS);
        status = EkycAttemptStatus.VERIFIED;
        submittedAt = now;
        decidedAt = now;
        updatedAt = now;
    }

    /** Gửi duyệt tay kèm bản nháp (đã bỏ dữ liệu sinh trắc) để nhân viên đối chiếu. */
    public void submitForReview(String draftJson, Instant now) {
        requireStatus(EkycAttemptStatus.IN_PROGRESS);
        status = EkycAttemptStatus.MANUAL_REVIEW;
        draftEncrypted = draftJson;
        submittedAt = now;
        updatedAt = now;
    }

    public void approve(UUID reviewer, String note, Instant now) {
        requireStatus(EkycAttemptStatus.MANUAL_REVIEW);
        status = EkycAttemptStatus.APPROVED;
        decide(reviewer, note, now);
    }

    public void reject(UUID reviewer, String note, Instant now) {
        requireStatus(EkycAttemptStatus.MANUAL_REVIEW);
        status = EkycAttemptStatus.REJECTED;
        decide(reviewer, note, now);
    }

    /** Hết hạn lưu: xoá bản nháp còn sót của lượt đã có quyết định (tối thiểu hoá dữ liệu). */
    public void clearDraft(Instant now) {
        if (draftEncrypted != null) {
            draftEncrypted = null;
            updatedAt = now;
        }
    }

    private void decide(UUID reviewer, String note, Instant now) {
        reviewerKeycloakId = reviewer;
        reviewNote = truncate(note, 1000);
        decidedAt = now;
        updatedAt = now;
    }

    private void requireStatus(EkycAttemptStatus expected) {
        if (status != expected) {
            throw new IllegalStateException("Lượt eKYC không ở trạng thái " + expected + " mà là " + status);
        }
    }

    private static String truncate(String value, int max) {
        if (value == null) {
            return null;
        }
        return value.length() <= max ? value : value.substring(0, max);
    }
}
