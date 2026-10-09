package com.finora.user.dto.request;

import com.finora.common.security.pin.PinScope;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Nhập mã PIN để lấy pin-token cho một loại thao tác nhạy cảm.
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class VerifyPinRequest {

    @NotBlank
    @Pattern(regexp = "[0-9]{6}", message = "Mã PIN gồm đúng 6 chữ số")
    private String pin;

    @NotNull
    private PinScope scope;
}
