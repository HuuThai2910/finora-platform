package com.finora.user.dto.request;

import com.finora.user.domain.Gender;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDate;

/**
 * Yêu cầu cập nhật hồ sơ cá nhân — tất cả các trường đều nullable (cập nhật từng phần).
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class UpdateProfileRequest {

    @Size(max = 255)
    private String fullName;

    private LocalDate dateOfBirth;

    private Gender gender;

    @Size(max = 500)
    private String placeOfOrigin;

    private String address;

    /** Số điện thoại 10 chữ số, bắt đầu bằng 0 */
    @Pattern(regexp = "^0\\d{9}$", message = "Số điện thoại phải 10 chữ số, bắt đầu bằng 0")
    private String phone;
}
