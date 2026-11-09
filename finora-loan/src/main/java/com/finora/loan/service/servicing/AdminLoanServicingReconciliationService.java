package com.finora.loan.service.servicing;

import com.finora.loan.domain.servicing.enums.ReconciliationIncidentStatus;
import com.finora.loan.domain.servicing.enums.ReconciliationIncidentType;
import com.finora.loan.dto.response.PageResponse;
import com.finora.loan.dto.servicing.response.AdminLoanServicingReconciliationResponse;
import com.finora.loan.dto.servicing.response.LoanReconciliationIncidentResponse;

/**
 * Màn đối soát servicing của admin: projection stale, incident đối soát và chạy lại đồng bộ một khoản vay từ
 * Fineract.
 */
public interface AdminLoanServicingReconciliationService {

    /** Danh sách projection stale dùng hai query cố định, không gọi Fineract theo từng dòng. */
    PageResponse<AdminLoanServicingReconciliationResponse> listStale(int page, int size);

    /**
     * Số liệu FINORA đang lưu của một khoản vay so với lịch đồng bộ hệ thống lõi (cờ {@code stale}), chỉ đọc database,
     * không gọi Fineract. Dùng cho phần "Sau giải ngân" của trang hồ sơ vay.
     */
    AdminLoanServicingReconciliationResponse status(String loanNumber);

    /** Gọi GET Fineract ngoài transaction; lặp lại an toàn và không phát sinh repayment/disbursement. */
    AdminLoanServicingReconciliationResponse reconcile(String loanNumber);

    /** Queue incident chỉ đọc; admin khắc phục nguyên nhân rồi chạy reconcile theo loanNumber. */
    PageResponse<LoanReconciliationIncidentResponse> listIncidents(
            ReconciliationIncidentStatus status, ReconciliationIncidentType type, int page, int size);

    /**
     * Sai lệch của đúng một khoản vay theo trạng thái ({@code null} là đang mở), cập nhật lâu nhất trước.
     *
     * @param loanNumber mã khoản vay {@code LN-…}
     */
    PageResponse<LoanReconciliationIncidentResponse> listIncidentsByLoan(
            String loanNumber, ReconciliationIncidentStatus status, int page, int size);
}
