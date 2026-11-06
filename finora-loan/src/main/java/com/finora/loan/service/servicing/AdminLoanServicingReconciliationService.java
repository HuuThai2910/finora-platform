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

    /** Gọi GET Fineract ngoài transaction; lặp lại an toàn và không phát sinh repayment/disbursement. */
    AdminLoanServicingReconciliationResponse reconcile(String loanNumber);

    /** Queue incident chỉ đọc; admin khắc phục nguyên nhân rồi chạy reconcile theo loanNumber. */
    PageResponse<LoanReconciliationIncidentResponse> listIncidents(
            ReconciliationIncidentStatus status, ReconciliationIncidentType type, int page, int size);
}
