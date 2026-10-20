package com.finora.investment.dto.response;

import java.math.BigDecimal;
import java.util.List;

/**
 * Tổng quan danh mục đầu tư.
 *
 * <p>Đây là read model dựng từ commitment và Note của chính Investment Service, không
 * join sang service khác (07-service-boundaries.md).</p>
 *
 * <p>Danh mục gồm mọi khoản vay nhà đầu tư có Note (R3-W2): ACTIVE (đang trả bình thường), DEFAULTED
 * (nhóm nợ 3–5, tức nợ xấu) và CLOSED (đã thu hồi đủ gốc, giữ lại để không mất lịch sử gốc/lãi đã nhận).
 * Vốn đang đầu tư gồm cả dư nợ của khoản nợ xấu vì đó vẫn là tiền nhà đầu tư chưa thu hồi; ba trường
 * {@code defaulted*} nêu riêng phần nợ xấu đó. Lãi suất bình quân và số Note ACTIVE chỉ tính phần còn sinh lãi.</p>
 *
 * <p>Ba trường cuối thêm cho AI-CHAT-001. Chỉ thêm, không đổi nghĩa trường cũ, nên app bản cũ
 * vẫn đọc được.</p>
 *
 * @param pendingAmount          vốn đã cam kết mà chưa có Note nào được phát hành (khoản vay chưa giải ngân)
 * @param investedAmount         dư nợ gốc còn nằm trong các Note, gồm cả Note DEFAULTED
 * @param principalRepaid        gốc đã thu hồi trên mọi vị thế
 * @param interestReceived       lãi đã nhận trên mọi vị thế
 * @param prepaymentFeeReceived  phần phí trả trước được chia cho nhà đầu tư
 * @param totalReceived          {@code principalRepaid + interestReceived + prepaymentFeeReceived}
 * @param activeNoteCount        số Note ACTIVE
 * @param positionCount          số vị thế trong danh mục, gồm cả vị thế DEFAULTED và CLOSED
 * @param weightedAverageRate    lãi suất bình quân theo dư nợ, chỉ tính Note ACTIVE
 * @param positions              mọi vị thế; {@code status} là ACTIVE, DEFAULTED hoặc CLOSED
 * @param defaultedAmount        dư nợ gốc của các vị thế DEFAULTED (một phần của {@code investedAmount})
 * @param defaultedNoteCount     số Note DEFAULTED
 * @param defaultedPositionCount số vị thế DEFAULTED
 */
public record PortfolioResponse(
        String pendingAmount,
        String investedAmount,
        String principalRepaid,
        String interestReceived,
        String prepaymentFeeReceived,
        String totalReceived,
        int activeNoteCount,
        int positionCount,
        BigDecimal weightedAverageRate,
        List<PortfolioPositionResponse> positions,
        String defaultedAmount,
        int defaultedNoteCount,
        int defaultedPositionCount
) {
}
