package com.finora.loan.dto.application.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** Borrower xác nhận đúng phiên bản/hash điều khoản đang hiển thị, không chấp nhận dữ liệu đã cũ. */
public record ConfirmLoanTermsRequest(
        @NotNull Long applicationVersion,
        @NotBlank @Size(max = 50) String termsVersion,
        @NotBlank @Size(min = 64, max = 64) String termsHash
) {
}
