package com.finora.loan.controller;

import com.finora.common.statistics.StatisticsBucket;
import com.finora.common.statistics.StatisticsPeriod;
import com.finora.loan.dto.statistics.response.LoanStatisticsSeriesResponse;
import com.finora.loan.dto.statistics.response.LoanStatisticsSummaryResponse;
import com.finora.loan.service.statistics.AdminLoanStatisticsService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Thống kê danh mục cho vay cho trang quản trị. Quyền ADMIN được chặn ở {@code SecurityConfig}
 * ({@code /api/v1/admin/**}) và kiểm lại trong service như các API admin khác của module.
 */
@RestController
@RequestMapping("/api/v1/admin/loan-statistics")
@RequiredArgsConstructor
public class AdminLoanStatisticsController {
    private final AdminLoanStatisticsService service;

    @GetMapping("/summary")
    public LoanStatisticsSummaryResponse summary() {
        return service.summary();
    }

    /**
     * Tham số nhận dạng chuỗi rồi tự parse: nếu để Spring đổi kiểu, ngày/bucket sai định dạng rơi vào
     * handler chung và thành 500, trong khi web cần 400 kèm mã để hiện lỗi ngay trong ô biểu đồ.
     */
    @GetMapping("/series")
    public LoanStatisticsSeriesResponse series(
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to,
            @RequestParam(required = false) String bucket) {
        return service.series(StatisticsPeriod.parseDate(from, "from"), StatisticsPeriod.parseDate(to, "to"), StatisticsBucket.parse(bucket));
    }



}
