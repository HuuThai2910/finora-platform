package com.finora.loan.service.application;

import com.finora.loan.dto.application.request.ConfirmLoanTermsRequest;
import com.finora.loan.dto.application.request.CreateLoanApplicationRequest;
import com.finora.loan.dto.application.request.DeclineLoanTermsRequest;
import com.finora.loan.dto.application.request.WithdrawLoanApplicationRequest;
import com.finora.loan.dto.application.response.LoanApplicationHistoryResponse;
import com.finora.loan.dto.application.response.LoanApplicationResponse;
import com.finora.loan.dto.application.response.LoanPurposeResponse;
import com.finora.loan.dto.response.PageResponse;

import java.util.List;

/** Hợp đồng nghiệp vụ cho luồng nộp và theo dõi hồ sơ vay của borrower. */
public interface LoanApplicationService {

    /**
     * Tạo thẳng hồ sơ SUBMITTED và lưu lịch trả Fineract làm bằng chứng tính toán.
     *
     * <p>Cuộc gọi mạng được thực hiện ngoài transaction database. Sau khi nhận kết quả,
     * service khóa lại Product và tái kiểm tra version/mapping trước khi ghi nguyên tử
     * Application, calculation snapshot và status history.</p>
     */
    LoanApplicationResponse submit(CreateLoanApplicationRequest request, String idempotencyKey);

    /**
     * Trước quyết định: đóng hồ sơ bằng WITHDRAWN. Sau duyệt nhưng listing còn mở: giữ quyết định APPROVED
     * làm audit, đóng trục funding và phát event để Investment hủy commitment/nhả hold.
     */
    LoanApplicationResponse withdraw(String applicationNumber, WithdrawLoanApplicationRequest request);

    LoanApplicationResponse acceptTerms(
            String applicationNumber,
            ConfirmLoanTermsRequest request,
            String idempotencyKey
    );

    LoanApplicationResponse declineTerms(
            String applicationNumber,
            DeclineLoanTermsRequest request,
            String idempotencyKey
    );

    /** Đọc chi tiết sau khi domain xác nhận borrower hiện tại sở hữu hồ sơ. */
    LoanApplicationResponse getMine(String applicationNumber);

    /**
     * Danh sách không tải calculation snapshot cho từng dòng, nhờ đó không phát sinh N+1.
     * Chi tiết lịch tính được trả ở endpoint chi tiết một hồ sơ.
     */
    PageResponse<LoanApplicationResponse> listMine(int page, int size);

    /** Timeline luôn phân trang và kiểm tra ownership trước query để không lộ lịch sử người khác. */
    PageResponse<LoanApplicationHistoryResponse> history(String applicationNumber, int page, int size);

    /** Trả allowlist dùng chung cho UI và AI mapper, tránh client tự tạo purposeCode không hợp lệ. */
    List<LoanPurposeResponse> purposes();
}
