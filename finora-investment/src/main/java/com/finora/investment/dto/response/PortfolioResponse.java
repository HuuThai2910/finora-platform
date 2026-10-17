package com.finora.investment.dto.response;

import java.math.BigDecimal;
import java.util.List;

/**
 * Tổng quan danh mục đầu tư.
 *
 * <p>Đây là read model dựng từ commitment và Note của chính Investment Service, không
 * join sang service khác (07-service-boundaries.md).</p>
 *
 * <p>Danh mục gồm các Note nhà đầu tư đang nắm và còn dư nợ: ACTIVE (khoản vay đang trả bình
 * thường) và DEFAULTED (khoản vay ở nhóm nợ 3–5, tức nợ xấu). Dư nợ tách hai phần để nợ xấu không
 * lẫn vào "vốn đang đầu tư" và lãi suất bình quân; tiền đã nhận về thì cộng trên mọi vị thế vì đó
 * là tiền thật đã vào ví. Note CLOSED (đã thu hồi đủ gốc) không còn trong danh mục.</p>
 *
 * <p>Ba trường cuối thêm cho AI-CHAT-001. Chỉ thêm, không đổi nghĩa trường cũ, nên app bản cũ
 * vẫn đọc được.</p>
 *
 * @param pendingAmount          vốn đã cam kết mà chưa có Note nào được phát hành (khoản vay chưa giải ngân)
 * @param investedAmount         dư nợ gốc của các Note ACTIVE
 * @param principalRepaid        gốc đã thu hồi của các Note đang nắm, gồm cả Note DEFAULTED
 * @param interestReceived       lãi đã nhận của các Note đang nắm, gồm cả Note DEFAULTED
 * @param totalReceived          {@code principalRepaid + interestReceived}
 * @param activeNoteCount        số Note ACTIVE
 * @param positionCount          số khoản vay có Note ACTIVE
 * @param weightedAverageRate    lãi suất bình quân theo dư nợ, chỉ tính trên Note ACTIVE
 * @param positions              mọi vị thế, kể cả vị thế nợ xấu ({@code status = "DEFAULTED"})
 * @param defaultedAmount        dư nợ gốc còn nằm trong các Note DEFAULTED
 * @param defaultedNoteCount     số Note DEFAULTED
 * @param defaultedPositionCount số khoản vay đang nợ xấu trong danh mục
 */
public record PortfolioResponse(
        String pendingAmount,
        String investedAmount,
        String principalRepaid,
        String interestReceived,
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
