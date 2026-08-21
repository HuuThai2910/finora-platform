package com.finora.user.dto.request;

import com.finora.user.domain.Gender;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDate;

/**
 * Dữ liệu từ chip NFC trên CCCD hoặc nhập tay từ form eKYC.
 * <p>
 * Sau khi xác minh, hệ thống sẽ hash số CCCD để tra cứu và mã hoá bản gốc để lưu trữ.
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class CccdDataRequest {

    /** Số căn cước công dân — đúng 12 chữ số */
    @NotBlank
    @Pattern(regexp = "^\\d{12}$", message = "Số CCCD phải đúng 12 chữ số")
    private String idNumber;

    @NotBlank
    @Size(max = 255)
    private String fullName;

    @NotNull
    private LocalDate dateOfBirth;

    @NotNull
    private Gender gender;

    @Size(max = 500)
    private String placeOfOrigin;

    private String address;
}
