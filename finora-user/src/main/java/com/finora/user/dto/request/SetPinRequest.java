package com.finora.user.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Tạo mã PIN giao dịch lần đầu.
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class SetPinRequest {

    @NotBlank
    @Pattern(regexp = "[0-9]{6}", message = "Mã PIN gồm đúng 6 chữ số")
    private String pin;
}
