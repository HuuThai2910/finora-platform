package com.finora.investment.controller;

import com.finora.common.statistics.StatisticsBucket;
import com.finora.common.statistics.StatisticsPeriod;
import com.finora.investment.dto.response.InvestmentStatisticsSeriesResponse;
import com.finora.investment.dto.response.InvestmentStatisticsSummaryResponse;
import com.finora.investment.service.statistics.InvestmentStatisticsService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Thống kê sàn gọi vốn và chợ Notes cho trang quản trị (STATS-001). Chỉ đọc.
 * Nằm dưới {@code /investments/admin/**} nên {@code SecurityConfig} chỉ cho vai trò ADMIN.
 */
@RestController
@RequestMapping("/api/v1/investments/admin/statistics")
@RequiredArgsConstructor
public class InvestmentStatisticsAdminController {

    private final InvestmentStatisticsService statisticsService;

    /** Ảnh chụp hiện tại: listing theo trạng thái, vốn đang gom, Note và Auto-Invest đang bật. */
    @GetMapping("/summary")
    public InvestmentStatisticsSummaryResponse summary() {
        return statisticsService.summary();
    }

    /**
     * Chuỗi theo cột thời gian.
     *
     * <p>Tham số nhận dạng chuỗi rồi tự đọc: nếu để Spring tự chuyển kiểu thì {@code bucket=YEAR} hoặc ngày
     * sai định dạng rơi vào handler lỗi chung và thành 500, trong khi đây là lỗi đầu vào của người gọi.</p>
     *
     * @param from   ngày bắt đầu {@code YYYY-MM-DD}, bỏ trống là 30 ngày gần nhất
     * @param to     ngày kết thúc {@code YYYY-MM-DD}, bỏ trống là hôm nay theo giờ Việt Nam
     * @param bucket {@code DAY} (mặc định), {@code WEEK} hoặc {@code MONTH}
     */
    @GetMapping("/series")
    public InvestmentStatisticsSeriesResponse series(
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to,
            @RequestParam(required = false) String bucket) {
        return statisticsService.series(StatisticsPeriod.parseDate(from, "from"), StatisticsPeriod.parseDate(to, "to"), StatisticsBucket.parse(bucket));
    }


}
