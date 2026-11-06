package com.finora.loan.service.core;

import com.finora.loan.dto.core.request.RepaymentPreviewRequest;
import com.finora.loan.dto.core.response.RepaymentPreviewResponse;

/**
 * Tính lịch trả dự kiến của một Product qua core lending (Fineract).
 */
public interface RepaymentPreviewService {

    /**
     * Đọc scalar Product/mapping trước rồi đóng transaction repository; HTTP Fineract không giữ DB connection.
     */
    RepaymentPreviewResponse preview(long productId, RepaymentPreviewRequest request);
}
