package com.finora.user.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * Một object bằng chứng trên kho S3 — bảng {@code ekyc_evidence}.
 * <p>
 * Bảng chỉ giữ metadata: object key (không chứa PII), SHA-256 đúng những byte server đã phân tích,
 * kích thước, kiểu nội dung và giai đoạn lưu. Byte thật chỉ nằm trên kho lưu trữ. Khi object bị xoá
 * (hết hạn lưu, rút đồng ý, lượt bỏ dở) bản ghi vẫn còn với {@code deleted_at} để giữ dấu vết.
 */
@Entity
@Table(name = "ekyc_evidence")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class EkycEvidence {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "attempt_id", nullable = false, updatable = false)
    private Long attemptId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20, updatable = false)
    private EkycEvidenceKind kind;

    /** Nhãn khung hình (FRONTAL, TURN_LEFT…, STILL) — chỉ để người duyệt đọc nhanh. */
    @Column(length = 30, updatable = false)
    private String label;

    @Column(name = "object_key", nullable = false, unique = true, length = 300, updatable = false)
    private String objectKey;

    @Column(nullable = false, length = 64, updatable = false)
    private String sha256;

    @Column(name = "size_bytes", nullable = false, updatable = false)
    private long sizeBytes;

    @Column(name = "content_type", nullable = false, length = 60, updatable = false)
    private String contentType;

    @Convert(converter = EkycEvidenceStage.DbConverter.class)
    @Column(nullable = false, length = 10)
    private EkycEvidenceStage stage;

    @Column(name = "deleted_at")
    private Instant deletedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    public static EkycEvidence stored(Long attemptId, EkycEvidenceKind kind, String label, String objectKey,
                                      String sha256, long sizeBytes, String contentType,
                                      EkycEvidenceStage stage, Instant now) {
        if (attemptId == null || kind == null || objectKey == null || sha256 == null
                || contentType == null || stage == null || now == null) {
            throw new IllegalArgumentException("Thiếu dữ liệu bắt buộc của bằng chứng eKYC");
        }
        EkycEvidence evidence = new EkycEvidence();
        evidence.attemptId = attemptId;
        evidence.kind = kind;
        evidence.label = label == null ? null : label.substring(0, Math.min(label.length(), 30));
        evidence.objectKey = objectKey;
        evidence.sha256 = sha256;
        evidence.sizeBytes = sizeBytes;
        evidence.contentType = contentType;
        evidence.stage = stage;
        evidence.createdAt = now;
        evidence.updatedAt = now;
        return evidence;
    }

    public boolean isDeleted() {
        return deletedAt != null;
    }

    /** Lượt đã có kết quả cuối: chuyển sang giai đoạn giữ theo hạn lưu. */
    public void markFinal(Instant now) {
        if (stage != EkycEvidenceStage.FINAL && deletedAt == null) {
            stage = EkycEvidenceStage.FINAL;
            updatedAt = now;
        }
    }

    /** Object đã bị xoá khỏi kho lưu trữ. */
    public void markDeleted(Instant now) {
        if (deletedAt == null) {
            deletedAt = now;
            updatedAt = now;
        }
    }
}
