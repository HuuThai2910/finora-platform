package com.finora.investment.dto.response;

import java.time.Instant;

/**
 * Một tin đăng bán Note trên chợ thứ cấp.
 *
 * <p>Tiền trả về dạng chuỗi decimal theo chuẩn của hệ thống, để không mất chính xác khi qua
 * JSON. Kèm luôn thông tin khoản vay gốc (lãi suất, kỳ hạn, hạng) vì người mua cần chúng để
 * quyết định mà không phải gọi thêm một lượt.</p>
 *
 * @param defaulted         Note đang nợ xấu — giao diện phải cảnh báo rõ, không chỉ tô màu
 * @param sellerProceeds    tiền người bán thực nhận sau phí; chỉ có khi tin đã bán
 * @param estimatedFee      phí dự kiến nếu bán ở giá đang treo, để người bán thấy trước
 * @param estimatedProceeds tiền dự kiến nhận sau phí, để người bán không phải tự tính
 */
public record NoteListingResponse(
        String listingReference,
        String noteNumber,
        Long loanId,
        String sellerId,

        String askingPrice,
        String outstandingPrincipal,
        String defaultedReason,
        boolean defaulted,

        String annualInterestRate,
        Integer termMonths,
        String creditGrade,

        String estimatedFee,
        String estimatedProceeds,

        String status,
        String buyerId,
        String soldPrice,
        String platformFee,
        String sellerProceeds,
        Instant soldAt,
        Instant createdAt
) {
}
