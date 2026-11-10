package com.finora.loan.service.contract;

import com.finora.loan.dto.contract.response.AdminContractSigningPolicyResponse;
import com.finora.loan.dto.contract.response.AdminLoanContractResponse;

/**
 * Hợp đồng vay cho màn quản trị: chỉ đọc, không ký hay từ chối thay bên nào. Quyền kiểm ở tầng URL
 * ({@code SecurityConfig}): quyền Xem "Gọi vốn" hoặc quyền đọc hồ sơ vay.
 */
public interface AdminLoanContractService {

    /** Hạn ký, mã băm, nhà cung cấp chữ ký số và tình hình ký của từng bên; 404 khi không có hợp đồng. */
    AdminLoanContractResponse detail(String contractNumber);

    /** PDF hiện hành của hợp đồng, cùng bản người vay/nhà đầu tư tải được; 404 khi chưa dựng PDF. */
    LoanContractPdfContent document(String contractNumber);

    /** Nhà cung cấp chữ ký số và thời hạn ký đang cấu hình, cho khoản vay chưa có hợp đồng. */
    AdminContractSigningPolicyResponse signingPolicy();
}
