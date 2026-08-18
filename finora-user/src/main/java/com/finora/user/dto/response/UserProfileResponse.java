package com.finora.user.dto.response;

import com.finora.user.domain.Gender;
import com.finora.user.domain.UserRole;

import java.time.LocalDate;

/**
 * Phản hồi thông tin hồ sơ người dùng.
 * <p>
 * Các trường {@code idNumber} (số CCCD) và {@code phone} (số điện thoại) đã được giải mã —
 * chỉ hiển thị cho chính chủ hồ sơ hoặc admin.
 */
public record UserProfileResponse(

        Long id,
        String email,
        String fullName,
        LocalDate dateOfBirth,
        Gender gender,
        String placeOfOrigin,
        String address,

        /** Số CCCD đã giải mã — chỉ hiển thị cho chủ hồ sơ hoặc admin */
        String idNumber,

        /** Số điện thoại đã giải mã — chỉ hiển thị cho chủ hồ sơ hoặc admin */
        String phone,

        UserRole role,
        boolean profileCompleted
) {
}
