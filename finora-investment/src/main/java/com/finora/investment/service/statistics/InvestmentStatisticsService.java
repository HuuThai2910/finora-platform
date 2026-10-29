package com.finora.investment.service.statistics;

import com.finora.common.statistics.StatisticsBucket;
import com.finora.investment.dto.response.InvestmentStatisticsSeriesResponse;
import com.finora.investment.dto.response.InvestmentStatisticsSummaryResponse;

import java.time.LocalDate;

/**
 * Số liệu sàn gọi vốn, Notes và Auto-Invest cho trang thống kê quản trị (STATS-001). Chỉ đọc.
 */
public interface InvestmentStatisticsService {

    InvestmentStatisticsSummaryResponse summary();

    /**
     * Chuỗi theo cột trong khoảng {@code from..to}: bốn câu {@code GROUP BY} (vốn góp, listing đủ vốn,
     * khớp lệnh, Auto-Invest) rồi ghép theo ngày đầu cột và trải đủ mọi cột.
     *
     * @throws com.finora.common.exception.BusinessException 400 {@code STATISTICS_RANGE_INVALID} khi khoảng sai
     */
    InvestmentStatisticsSeriesResponse series(LocalDate from, LocalDate to, StatisticsBucket bucket);
}
