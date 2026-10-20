package com.finora.user.dto.request;

import com.finora.common.security.pin.PinScope;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Nhập mã PIN để lấy pin-token cho một thao tác nhạy cảm cụ thể.
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

    /**
     * {@code Idempotency-Key} mà app sẽ gửi cùng thao tác (D38): token chỉ hợp lệ cho đúng thao tác này, nên lộ token
     * không đủ để gửi lệnh khác cùng loại.
     */
    @NotBlank
    @Size(min = 8, max = 150)
    private String requestKey;
}
