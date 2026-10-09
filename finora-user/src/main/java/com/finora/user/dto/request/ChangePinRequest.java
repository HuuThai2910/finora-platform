package com.finora.user.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Đổi mã PIN khi vẫn nhớ PIN cũ.
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class ChangePinRequest {

    @NotBlank
    @Pattern(regexp = "[0-9]{6}", message = "Mã PIN gồm đúng 6 chữ số")
    private String currentPin;

    @NotBlank
    @Pattern(regexp = "[0-9]{6}", message = "Mã PIN gồm đúng 6 chữ số")
    private String newPin;
}
