package com.finora.payment.dto.response;

import com.finora.payment.domain.bankaccount.entity.PaymentBankAccount;
import com.finora.payment.service.bank.BankInfo;

import java.time.Instant;
import java.util.UUID;

/**
 * Tài khoản ngân hàng đã liên kết; số tài khoản luôn ở dạng che.
 *
 * @param defaultAccount tài khoản được chọn sẵn khi rút tiền
 */
public record BankAccountResponse(
        UUID bankAccountId,
        String bankCode,
        String bankName,
        String bankLogoUrl,
        String accountNumberMasked,
        String accountHolderName,
        boolean defaultAccount,
        Instant linkedAt
) {
    /** {@code bank} rỗng khi ngân hàng đã rời danh bạ: hiện mã ngân hàng thay cho tên. */
    public static BankAccountResponse from(PaymentBankAccount value, BankInfo bank) {
        return new BankAccountResponse(value.getBankAccountId(), value.getBankCode(),
                bank == null ? value.getBankCode() : bank.shortName(), bank == null ? null : bank.logo(),
                value.getAccountNumberMasked(), value.getAccountHolderName(), value.isDefaultAccount(),
                value.getLinkedAt());
    }
}
