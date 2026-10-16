package com.finora.user.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.UUID;

/**
 * Nhật ký mỗi lần nhân viên mở hồ sơ hoặc một bằng chứng eKYC — bảng {@code ekyc_evidence_access_log}.
 * <p>
 * Điều khoản eKYC cam kết "mỗi lần xem đều được ghi lại"; {@code evidenceId = null} nghĩa là mở trang
 * chi tiết lượt (xem bản nháp đã giải mã) chứ chưa mở file nào.
 */
@Entity
@Table(name = "ekyc_evidence_access_log")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class EkycEvidenceAccessLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "attempt_id", nullable = false, updatable = false)
    private Long attemptId;

    @Column(name = "evidence_id", updatable = false)
    private Long evidenceId;

    @Column(name = "admin_keycloak_id", nullable = false, updatable = false)
    private UUID adminKeycloakId;

    @Column(name = "accessed_at", nullable = false, updatable = false)
    private Instant accessedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    public static EkycEvidenceAccessLog of(Long attemptId, Long evidenceId, UUID adminKeycloakId, Instant now) {
        if (attemptId == null || adminKeycloakId == null || now == null) {
            throw new IllegalArgumentException("Thiếu dữ liệu bắt buộc của nhật ký truy cập bằng chứng");
        }
        EkycEvidenceAccessLog log = new EkycEvidenceAccessLog();
        log.attemptId = attemptId;
        log.evidenceId = evidenceId;
        log.adminKeycloakId = adminKeycloakId;
        log.accessedAt = now;
        log.createdAt = now;
        log.updatedAt = now;
        return log;
    }
}
