package com.finora.loan.service.statistics;

import com.finora.common.statistics.StatisticsBucket;
import com.finora.loan.dto.statistics.response.LoanStatisticsSeriesResponse;
import com.finora.loan.dto.statistics.response.LoanStatisticsSummaryResponse;

import java.time.LocalDate;

/**
 * Số liệu thống kê danh mục cho vay cho quản trị viên (STATS-001): số liệu hiện tại và chuỗi theo thời gian.
 */
public interface AdminLoanStatisticsService {

    /** Số liệu hiện tại: hồ sơ, danh mục, thu hồi nợ, cơ cấu, đối soát và phân bố điểm tín dụng. */
    LoanStatisticsSummaryResponse summary();

    /**
     * Chuỗi số liệu theo từng cột thời gian của khoảng {@code [from, to]}: hồ sơ, quyết định, giải ngân, phễu và dư nợ.
     * <p>
     * Không mở transaction quanh cả hàm: phần dư nợ theo ngày gọi Fineract cho từng khoản vay (lần đầu
     * hoặc khi có giao dịch mới), không giữ kết nối Neon trong lúc chờ core. Các câu SQL vẫn chạy chung
     * một transaction.
     *
     * @throws com.finora.common.exception.BusinessException 400 STATISTICS_RANGE_INVALID khi khoảng ngày sai
     */
    LoanStatisticsSeriesResponse series(LocalDate from, LocalDate to, StatisticsBucket bucket);
}
