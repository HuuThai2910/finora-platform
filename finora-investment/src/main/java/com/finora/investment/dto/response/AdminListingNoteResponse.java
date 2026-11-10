package com.finora.investment.dto.response;

/**
 * Một phần vốn của khoản vay trên tab Phần vốn của sổ lệnh quản trị: ai đang nắm giữ, ai góp ban đầu, đã đổi chủ mấy
 * lần và có đang rao bán không.
 *
 * @param holderId              nhà đầu tư đang nắm giữ (đã tính chuyển nhượng), chỉ để web mở hồ sơ khách hàng
 * @param holderName            họ tên người đang nắm giữ; null khi User chưa đọc được
 * @param originalInvestorId    nhà đầu tư đã góp vốn ở đợt gọi vốn (chủ đầu tiên của phần vốn)
 * @param transferCount         số lần phần vốn đã đổi chủ qua chợ
 * @param askPricePercent       giá của lệnh bán đang khóa phần vốn (% dư nợ gốc còn lại); null khi không rao bán
 */
public record AdminListingNoteResponse(
        Long noteId,
        String noteNumber,
        String status,
        String holderId,
        String holderName,
        String originalInvestorId,
        String originalInvestorName,
        long transferCount,
        String askPricePercent
) {
}
