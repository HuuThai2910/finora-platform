package com.finora.loan.service.servicing;

import com.finora.loan.dto.response.PageResponse;
import com.finora.loan.dto.servicing.response.LoanRepaymentScheduleResponse;
import com.finora.loan.dto.servicing.response.LoanServicingSummaryResponse;
import com.finora.loan.dto.servicing.response.PendingLoanActivationResponse;

import java.util.List;

/**
 * Đọc khoản vay đang phục vụ của người vay hiện tại: danh sách, chi tiết, lịch trả và khoản đang chờ kích hoạt.
 */
public interface LoanServicingQueryService {

    /** Khoản vay của người vay hiện tại, mới giải ngân trước. */
    PageResponse<LoanServicingSummaryResponse> listMine(int page, int size);

    /**
     * Cho mobile phân biệt "chưa có khoản vay" với "đã giải ngân và đang hoàn tất lịch trả nợ".
     * COMPLETED không xuất hiện ở đây vì lúc đó {@link #listMine(int, int)} đã là nguồn hiển thị chuẩn.
     */
    List<PendingLoanActivationResponse> pendingMine();

    /** Chi tiết một khoản vay của người vay hiện tại. */
    LoanServicingSummaryResponse getMine(String loanNumber);

    /** Lịch trả nợ của một khoản vay, lấy từ snapshot lịch đã dùng để lập hợp đồng. */
    LoanRepaymentScheduleResponse schedule(String loanNumber);
}
