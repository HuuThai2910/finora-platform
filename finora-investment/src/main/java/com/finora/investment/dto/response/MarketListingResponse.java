package com.finora.investment.dto.response;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Một khoản vay trên sàn.
 *
 * <p>Định danh người vay duy nhất được công bố là họ tên ({@code borrowerFullName}); không có CCCD, liên hệ hay
 * địa chỉ.</p>
 *
 * <p>{@code applicationNumber} là mã hồ sơ công khai của Loan. App dùng nó để tải hồ sơ người vay
 * trực tiếp từ Loan; Investment không chép số liệu đó về đây. {@code applicationCode}/{@code contractCode} là mã hiển
 * thị ({@code HS-...}/{@code HĐ-...}) cho người dùng đọc; null với listing tạo trước khi Loan có mã.</p>
 *
 * <p>Tiền truyền dạng chuỗi decimal theo 04-conventions-contracts.md để client không mất
 * chính xác khi parse thành số thực.</p>
 */
public record MarketListingResponse(
        Long listingId,
        Long loanId,
        String applicationNumber,
        String purpose,
        String region,
        String creditGrade,
        Integer creditScore,
        String targetAmount,
        String committedAmount,
        String remainingAmount,
        BigDecimal fundedPercent,
        String annualInterestRate,
        Integer termMonths,
        String repaymentMethod,
        String noteDenomination,
        String minInvestmentAmount,
        String status,
        String contractNumber,
        String contractStatus,
        Instant fundingClosesAt,
        // Chỉ có khi status = CANCELLED: mã lý do Loan gửi trong LoanFundingCancelled, không phải văn bản tự do.
        Instant cancelledAt,
        String cancellationReason,
        // Có khi Loan đã xác nhận giải ngân (LoanDisbursed); trước mốc này tiền nhà đầu tư mới chỉ được giữ.
        Instant disbursedAt,
        // Họ tên người vay theo hồ sơ User; danh sách đọc theo lô (có bộ nhớ đệm), null khi User lỗi hoặc chưa có họ tên.
        String borrowerFullName,
        String applicationCode,
        String contractCode,
        // Mã LoanPurpose (EDUCATION, SMALL_BUSINESS…) để app chọn biểu tượng; null khi không nhận ra mục đích.
        String purposeCode,
        // Thời điểm khoản vay lên sàn (mở gọi vốn; với gọi vốn bổ sung vẫn là lần mở đầu tiên của listing).
        Instant fundingOpenedAt,
        // Số nhà đầu tư không trùng: phần vốn đang tính vào tổng, hoặc với khoản đã đóng/hủy là những người đã góp trước
        // khi được hoàn tiền. Null khi response không đọc số này (tạo listing từ event).
        Integer investorCount,
        // Số đã gọi được lúc đóng/hủy (trước khi hoàn tiền, khi đó committedAmount về 0); null khi listing chưa đóng/hủy.
        String raisedAmount
) {
}
