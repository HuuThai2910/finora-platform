package com.finora.loan.dto.restructure.response;

/**
 * Điều khoản và hạn mức gia hạn đã tính riêng cho một khoản vay.
 * {@code maximumExtraTerms} là cận nhỏ nhất còn lại của giới hạn mỗi lần, cộng dồn và tổng thời hạn hợp đồng.
 */
public record LoanRescheduleEligibilityResponse(
        String termsVersion,
        String termsText,
        String termsHash,
        int maximumTotalTerms,
        int currentTotalTerms,
        int maximumExtraTerms,
        boolean termExtensionAllowed,
        String termExtensionUnavailableReason
) {}
