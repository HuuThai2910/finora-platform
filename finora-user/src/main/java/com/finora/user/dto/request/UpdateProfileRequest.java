package com.finora.user.dto.request;

import com.finora.user.domain.Gender;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;

/**
 * Yêu cầu cập nhật hồ sơ cá nhân — tất cả các trường đều nullable (cập nhật từng phần).
 */
public record UpdateProfileRequest(

        @Size(max = 255)
        String fullName,

        LocalDate dateOfBirth,

        Gender gender,

        @Size(max = 500)
        String placeOfOrigin,

        String address,

        /** Số điện thoại 10 chữ số, bắt đầu bằng 0 */
        @Pattern(regexp = "^0\\d{9}$", message = "Số điện thoại phải 10 chữ số, bắt đầu bằng 0")
        String phone
) {
}
