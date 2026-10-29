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
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/**
 * Một lần người dùng đồng ý (hoặc đồng ý lại) điều khoản xử lý dữ liệu eKYC — bảng {@code ekyc_consents}.
 * <p>
 * Đây là bằng chứng đồng ý kiểm chứng được: {@code text_sha256} băm đúng nội dung đã hiển thị, nên
 * đổi một chữ trong điều khoản thì bản ghi cũ tự hết hiệu lực. Bản ghi không bị xoá khi rút lại;
 * chỉ ghi {@code withdrawn_at} để giữ lịch sử ai đã đồng ý điều gì, lúc nào.
 * <p>
 * Chỉ giữ {@code userProfileId} dạng số thay vì quan hệ JPA để không kéo cả hồ sơ khi đọc.
 */
@Entity
@Table(name = "ekyc_consents")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class EkycConsent {

    private static final String PURPOSE_SEPARATOR = ",";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_profile_id", nullable = false, updatable = false)
    private Long userProfileId;

    @Column(nullable = false, length = 40, updatable = false)
    private String version;

    /** Mã mục đích phân tách dấu phẩy, ví dụ {@code ID_DOCUMENT,FACE_BIOMETRIC}. */
    @Column(nullable = false, length = 200, updatable = false)
    private String purposes;

    @Column(name = "text_sha256", nullable = false, length = 64, updatable = false)
    private String textSha256;

    @Column(length = 20, updatable = false)
    private String platform;

    @Column(name = "app_version", length = 40, updatable = false)
    private String appVersion;

    @Column(name = "accepted_at", nullable = false, updatable = false)
    private Instant acceptedAt;

    @Column(name = "withdrawn_at")
    private Instant withdrawnAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    /** Ghi nhận người dùng đồng ý đúng phiên bản và đúng nội dung đã hiển thị. */
    public static EkycConsent accept(Long userProfileId, String version, List<String> purposes,
                                     String textSha256, String platform, String appVersion, Instant now) {
        if (userProfileId == null || version == null || textSha256 == null || now == null
                || purposes == null || purposes.isEmpty()) {
            throw new IllegalArgumentException("Thiếu dữ liệu bắt buộc của bản ghi đồng ý eKYC");
        }
        EkycConsent consent = new EkycConsent();
        consent.userProfileId = userProfileId;
        consent.version = version;
        consent.purposes = String.join(PURPOSE_SEPARATOR, purposes);
        consent.textSha256 = textSha256;
        consent.platform = platform;
        consent.appVersion = appVersion;
        consent.acceptedAt = now;
        consent.createdAt = now;
        consent.updatedAt = now;
        return consent;
    }

    /**
     * Còn hiệu lực khi chưa rút lại và khớp cả phiên bản lẫn hash nội dung hiện hành — chỉ khớp
     * phiên bản là chưa đủ, vì nội dung có thể đổi (ví dụ tắt Gemini) mà quên tăng phiên bản.
     */
    public boolean isActiveFor(String currentVersion, String currentTextSha256) {
        return withdrawnAt == null
                && Objects.equals(version, currentVersion)
                && Objects.equals(textSha256, currentTextSha256);
    }

    /** Rút lại sự đồng ý; gọi lại lần nữa không đổi mốc thời gian đã ghi. */
    public void withdraw(Instant now) {
        if (withdrawnAt == null) {
            withdrawnAt = now;
            updatedAt = now;
        }
    }

    public List<String> purposeList() {
        return Arrays.stream(purposes.split(PURPOSE_SEPARATOR))
                .map(String::trim)
                .filter(code -> !code.isEmpty())
                .toList();
    }
}
