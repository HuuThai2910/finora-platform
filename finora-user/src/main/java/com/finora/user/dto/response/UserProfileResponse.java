package com.finora.user.dto.response;

import com.finora.user.domain.Gender;
import com.finora.user.domain.UserRole;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

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
}
