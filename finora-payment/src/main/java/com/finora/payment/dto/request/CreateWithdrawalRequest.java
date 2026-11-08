package com.finora.payment.dto.request;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Lệnh rút tiền về tài khoản ngân hàng (R4). Nơi nhận là <b>một trong hai</b>:
 * <ul>
 *   <li>{@code bankAccountId}: tài khoản đã liên kết và xác thực OTP (BL-001) — cách app dùng;</li>
 *   <li>{@code bankCode} + {@code accountNumber} + {@code accountHolderName} gõ tay — giữ cho client cũ và test.</li>
 * </ul>
 * Số tài khoản chỉ đi trong body request, được mã hóa trước khi lưu và không bao giờ trả lại đầy đủ.
 */
public record CreateWithdrawalRequest(
        @NotNull @Positive BigDecimal amount,
        UUID bankAccountId,
        @Size(max = 20) String bankCode,
        @Pattern(regexp = "[0-9]{6,19}", message = "Số tài khoản gồm 6–19 chữ số") String accountNumber,
        @Size(min = 2, max = 100) String accountHolderName
) {
    /** Lệnh rút gõ tay số tài khoản (client cũ, test). */
    public CreateWithdrawalRequest(BigDecimal amount, String bankCode, String accountNumber, String accountHolderName) {
        this(amount, null, bankCode, accountNumber, accountHolderName);
    }

    /** Lệnh rút về tài khoản đã liên kết. */
    public static CreateWithdrawalRequest toLinkedAccount(BigDecimal amount, UUID bankAccountId) {
        return new CreateWithdrawalRequest(amount, bankAccountId, null, null, null);
    }
}
