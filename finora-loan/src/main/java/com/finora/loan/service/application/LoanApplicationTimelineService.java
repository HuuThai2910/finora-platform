package com.finora.loan.service.application;

import com.finora.loan.domain.application.entity.LoanApplication;
import com.finora.loan.dto.application.response.LoanApplicationTimelineItemResponse;

import java.util.List;

/**
 * Lịch sử xử lý đầy đủ của một hồ sơ cho màn quản trị: thẩm định, gọi vốn, ký hợp đồng (kể cả nhà đầu tư rút và gọi
 * vốn bổ sung), giải ngân, cơ cấu/gia hạn nợ, thu hồi nợ cho tới khi khoản vay đóng.
 */
public interface LoanApplicationTimelineService {

    /** Các mốc mới nhất ở đầu; đọc trong transaction của caller (chỉ đọc). */
    List<LoanApplicationTimelineItemResponse> timeline(LoanApplication application);
}
