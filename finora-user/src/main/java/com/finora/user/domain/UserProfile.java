package com.finora.user.domain;

import com.finora.user.support.CryptoConverter;
import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Objects;
import java.util.UUID;

/**
 * Thực thể hồ sơ người dùng — ánh xạ bảng {@code user_profiles}.
 * <p>
 * Các trường nhạy cảm (số CCCD, số điện thoại) được lưu dưới dạng:
 * <ul>
 *   <li>Hash SHA-256 (để tra cứu nhanh, không giải mã được)</li>
 *   <li>Bản mã hoá AES (để hiển thị lại cho chủ hồ sơ hoặc admin)</li>
 * </ul>
 */
@Entity
@Table(name = "user_profiles")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class UserProfile {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** ID người dùng trên Keycloak — dùng để đồng bộ xác thực */
    @Column(name = "keycloak_user_id", nullable = false, unique = true)
    private UUID keycloakUserId;

    /** Email đăng nhập — duy nhất toàn hệ thống */
    @Column(nullable = false, unique = true, length = 255)
    private String email;

    /** Họ và tên đầy đủ (theo CCCD hoặc do người dùng nhập) */
    @Column(name = "full_name", length = 255)
    private String fullName;

    /** Ngày sinh */
    @Column(name = "date_of_birth")
    private LocalDate dateOfBirth;

    /** Giới tính */
    @Enumerated(EnumType.STRING)
    @Column(length = 10)
    private Gender gender;

    /** Quê quán (theo CCCD) */
    @Column(name = "place_of_origin", length = 500)
    private String placeOfOrigin;

    /** Địa chỉ thường trú */
    @Column(columnDefinition = "TEXT")
    private String address;

    /** Hash SHA-256 của số CCCD — dùng để tra cứu trùng lặp */
    @Column(name = "id_number_hash", length = 64)
    private String idNumberHash;

    /** Số CCCD đã mã hoá AES — giải mã tự động qua {@link CryptoConverter} */
    @Convert(converter = CryptoConverter.class)
    @Column(name = "id_number_encrypted", length = 500)
    private String idNumberEncrypted;

    /** Hash SHA-256 của số điện thoại — dùng để tra cứu trùng lặp */
    @Column(name = "phone_hash", length = 64)
    private String phoneHash;

    /** Số điện thoại đã mã hoá AES — giải mã tự động qua {@link CryptoConverter} */
    @Convert(converter = CryptoConverter.class)
    @Column(name = "phone_encrypted", length = 500)
    private String phoneEncrypted;

    /** Vai trò — mặc định là BORROWER khi đăng ký */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    @Builder.Default
    private UserRole role = UserRole.BORROWER;

    /** Trạng thái xác minh eKYC */
    @Enumerated(EnumType.STRING)
    @Column(name = "ekyc_status", nullable = false)
    @Builder.Default
    private EkycStatus ekycStatus = EkycStatus.PENDING;

    /** Điểm khớp khuôn mặt (0-1) */
    @Column(name = "face_match_score")
    private Double faceMatchScore;

    /** Đã xác minh liveness (ảnh thật) */
    @Column(name = "liveness_verified", nullable = false)
    @Builder.Default
    private boolean livenessVerified = false;

    /** Đã xác minh giấy tờ (CCCD) */
    @Column(name = "document_verified", nullable = false)
    @Builder.Default
    private boolean documentVerified = false;

    /** Thời điểm hoàn thành eKYC */
    @Column(name = "ekyc_completed_at")
    private Instant ekycCompletedAt;

    /** Đánh dấu hồ sơ đã điền đầy đủ thông tin eKYC hay chưa */
    @Column(name = "profile_completed", nullable = false)
    @Builder.Default
    private boolean profileCompleted = false;

    /** Thời điểm tạo bản ghi — gán tự động khi persist */
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    /** Thời điểm cập nhật gần nhất — gán tự động khi persist hoặc update */
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    // ── eKYC state transitions ────────────────────────────────────────

    /**
     * Đánh dấu đã xác minh giấy tờ (CCCD hai mặt). Luồng hiện tại không có
     * xác minh khuôn mặt nên {@code livenessVerified}/{@code faceMatchScore}
     * giữ nguyên giá trị mặc định.
     */
    public void markEkycDocumentVerified() {
        this.ekycStatus = EkycStatus.VERIFIED;
        this.documentVerified = true;
        this.ekycCompletedAt = Instant.now();
    }

    // ── Lifecycle callbacks ──────────────────────────────────────────

    @PrePersist
    protected void onCreate() {
        Instant now = Instant.now();
        this.createdAt = now;
        this.updatedAt = now;
    }

    @PreUpdate
    protected void onUpdate() {
        this.updatedAt = Instant.now();
    }

    // ── equals / hashCode theo id (JPA best practice) ────────────────

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        UserProfile that = (UserProfile) o;
        return id != null && Objects.equals(id, that.id);
    }

    @Override
    public int hashCode() {
        return getClass().hashCode();
    }
}
