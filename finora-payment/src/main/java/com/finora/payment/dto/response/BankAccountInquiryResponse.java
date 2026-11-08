package com.finora.payment.dto.response;

/**
 * Kết quả tra cứu chủ tài khoản. {@code nameMatched} cho app báo ngay khi tài khoản không đứng tên người dùng; Payment
 * vẫn tự kiểm lại lúc liên kết.
 */
public record BankAccountInquiryResponse(
        String bankCode,
        String bankName,
        String accountNumberMasked,
        String accountHolderName,
        boolean nameMatched
) {
}
