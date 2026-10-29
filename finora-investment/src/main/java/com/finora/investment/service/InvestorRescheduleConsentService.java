package com.finora.investment.service;

import com.finora.common.dto.PageResponse;
import com.finora.investment.dto.response.InvestorRescheduleConsentResponse;

import java.util.UUID;

/**
 * Đề nghị cơ cấu khoản vay gửi tới nhà đầu tư đang giữ Note: xem danh sách, chi tiết và đồng ý/từ chối.
 */
public interface InvestorRescheduleConsentService {

    PageResponse<InvestorRescheduleConsentResponse> listMine(int page, int size);

    InvestorRescheduleConsentResponse detail(UUID requestId);

    InvestorRescheduleConsentResponse decide(UUID requestId, String idempotencyKey,
            boolean approve, String comment);
}
