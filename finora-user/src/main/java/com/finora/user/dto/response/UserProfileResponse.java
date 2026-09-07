package com.finora.user.dto.response;

import com.finora.user.domain.EkycStatus;
import com.finora.user.domain.Gender;
import com.finora.user.domain.UserRole;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.time.LocalDate;

/**
 * Phản hồi thông tin hồ sơ người dùng.
 * <p>
 * Các trường {@code idNumber} (số CCCD) và {@code phone} (số điện thoại) đã được giải mã —
 * chỉ hiển thị cho chính chủ hồ sơ hoặc admin.
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class UserProfileResponse {

    private Long id;
    private String email;
    private String fullName;
    private LocalDate dateOfBirth;
    private Gender gender;
    private String placeOfOrigin;
    private String address;

    /** Số CCCD đã giải mã — chỉ hiển thị cho chủ hồ sơ hoặc admin */
    private String idNumber;

    /** Số điện thoại đã giải mã — chỉ hiển thị cho chủ hồ sơ hoặc admin */
    private String phone;

    private UserRole role;
    private boolean profileCompleted;

    /** Trạng thái xác minh eKYC — màn hình quản trị lọc theo trường này */
    private EkycStatus ekycStatus;

    /** Đã xác minh giấy tờ CCCD hai mặt hay chưa */
    private boolean documentVerified;

    /** Thời điểm hoàn thành eKYC — null nếu chưa xác minh xong */
    private Instant ekycCompletedAt;

    /** Thời điểm tạo tài khoản */
    private Instant createdAt;

    /**
     * Tài khoản có đang bị khóa trên Keycloak hay không.
     * <p>
     * Không nằm trong DB service này — được nạp từ Keycloak khi admin xem danh
     * sách/chi tiết. Với API hồ sơ cá nhân ({@code /users/me}) trường này để null
     * vì người đang gọi đương nhiên không bị khóa.
     */
    private Boolean locked;
}
