package com.finora.loan.service.statistics;

import com.finora.loan.domain.servicing.model.LoanServicingHistory;
import com.finora.loan.repository.statistics.LoanStatisticsRepository.HistoryLoanRow;

import java.util.List;
import java.util.Map;

/**
 * Đọc lịch sử khoản vay từ Fineract cho thống kê dư nợ theo ngày, có cache trong bộ nhớ.
 */
public interface PortfolioHistoryReader {

    /** Lịch sử theo id khoản vay FINORA; khoản không đọc được thì vắng mặt trong map. */
    Map<Long, LoanServicingHistory> read(List<HistoryLoanRow> loans);
}
