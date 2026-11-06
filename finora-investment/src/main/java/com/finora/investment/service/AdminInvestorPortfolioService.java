package com.finora.investment.service;

import com.finora.common.dto.PageResponse;
import com.finora.investment.dto.response.AdminInvestorNoteResponse;

/** Danh mục Note của một nhà đầu tư cho nhân viên xem ở trang khách hàng (chỉ đọc). */
public interface AdminInvestorPortfolioService {

    /** Note nhà đầu tư đang giữ (mọi trạng thái), mới phát hành trước. */
    PageResponse<AdminInvestorNoteResponse> notesOf(String investorId, int page, int size);
}
