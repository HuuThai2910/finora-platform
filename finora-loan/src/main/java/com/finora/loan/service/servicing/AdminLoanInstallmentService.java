package com.finora.loan.service.servicing;

import com.finora.loan.dto.servicing.response.AdminLoanInstallmentsResponse;

/**
 * Trạng thái từng kỳ trả của khoản vay đã giải ngân, cho thẻ Trả nợ của trang Gọi vốn.
 */
public interface AdminLoanInstallmentService {

    /**
     * Các kỳ trả theo lịch hiện hành trên Fineract, kèm kỳ nào đã trả đủ, quá hạn hay chưa đến hạn.
     *
     * <p>Đọc lịch sử khoản vay qua bộ đọc có bộ nhớ đệm của thống kê dư nợ, nên mở lại trang không gọi lại core khi khoản
     * vay chưa có giao dịch mới. 404 khi không có khoản vay; 503 {@code LOAN_INSTALLMENTS_UNAVAILABLE} khi Fineract
     * không đọc được.</p>
     */
    AdminLoanInstallmentsResponse installments(String loanNumber);
}
