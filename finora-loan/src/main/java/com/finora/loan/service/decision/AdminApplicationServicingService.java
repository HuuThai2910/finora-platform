package com.finora.loan.service.decision;

import com.finora.loan.domain.servicing.model.ApplicationServicingSummary;

import java.util.Collection;
import java.util.Map;

/**
 * Tình trạng sau giải ngân của hồ sơ vay cho màn quản trị: danh sách hồ sơ hiện nhãn cảnh báo, trang chi tiết biết có
 * khoản vay để mở phần "Sau giải ngân". Chỉ đọc database của Loan, số query cố định theo trang.
 */
public interface AdminApplicationServicingService {

    /**
     * Tình trạng của từng hồ sơ theo ID; hồ sơ chưa vào giải ngân không có trong kết quả.
     *
     * @param applicationIds ID số của hồ sơ trên trang đang xem
     * @return bản đồ ID hồ sơ → tình trạng sau giải ngân
     */
    Map<Long, ApplicationServicingSummary> summariesOf(Collection<Long> applicationIds);

    /**
     * Tình trạng của một hồ sơ.
     *
     * @param applicationId ID số của hồ sơ
     * @return tình trạng; {@link ApplicationServicingSummary#NONE} khi hồ sơ chưa vào giải ngân
     */
    ApplicationServicingSummary summaryOf(Long applicationId);
}
