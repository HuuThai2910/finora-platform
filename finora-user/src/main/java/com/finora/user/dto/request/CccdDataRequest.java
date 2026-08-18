package com.finora.user.dto.request;

import com.finora.user.domain.Gender;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;

/**
 * Dữ liệu từ chip NFC trên CCCD hoặc nhập tay từ form eKYC.
 * <p>
 * Sau khi xác minh, hệ thống sẽ hash số CCCD để tra cứu và mã hoá bản gốc để lưu trữ.
 */
public record CccdDataRequest(

        /** Số căn cước công dân — đúng 12 chữ số */
        @NotBlank @Pattern(regexp = "^\\d{12}$", message = "Số CCCD phải đúng 12 chữ số")
        String idNumber,

        @NotBlank @Size(max = 255)
        String fullName,

        @NotNull
        LocalDate dateOfBirth,

        @NotNull
        Gender gender,

        @Size(max = 500)
        String placeOfOrigin,

        String address
) {
}
