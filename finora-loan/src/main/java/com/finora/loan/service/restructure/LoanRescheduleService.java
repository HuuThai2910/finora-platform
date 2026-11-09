package com.finora.loan.service.restructure;

import com.finora.loan.domain.restructure.enums.LoanRescheduleStatus;
import com.finora.loan.dto.response.PageResponse;
import com.finora.loan.dto.restructure.request.AdminLoanRescheduleDecisionRequest;
import com.finora.loan.dto.restructure.request.CreateLoanRescheduleRequest;
import com.finora.loan.dto.restructure.response.LoanReschedulePolicyResponse;
import com.finora.loan.dto.restructure.response.LoanRescheduleEligibilityResponse;
import com.finora.loan.dto.restructure.response.LoanRescheduleResponse;

import java.util.UUID;

/**
 * Yêu cầu cơ cấu khoản vay: người vay đọc điều khoản, gửi và xem yêu cầu của mình; quản trị duyệt (chuyển sang chờ nhà
 * đầu tư đồng ý) hoặc từ chối.
 */
public interface LoanRescheduleService {

    /** Điều khoản cơ cấu hiện hành (phiên bản, nội dung, hash) để người vay đọc và xác nhận. */
    LoanReschedulePolicyResponse policy();

    /** Điều khoản và số kỳ còn được gia hạn của đúng khoản vay thuộc người dùng hiện tại. */
    LoanRescheduleEligibilityResponse eligibility(String loanNumber);

    /** Người vay gửi yêu cầu cơ cấu cho khoản vay của mình; gửi lại cùng {@code Idempotency-Key} trả yêu cầu cũ. */
    LoanRescheduleResponse submit(String loanNumber, String idempotencyKey,
            CreateLoanRescheduleRequest input);

    /** Yêu cầu cơ cấu của người vay hiện tại cho một khoản vay, mới nhất trước. */
    PageResponse<LoanRescheduleResponse> listMine(String loanNumber, int page, int size);

    /** Danh sách yêu cầu cho quản trị, lọc tùy chọn theo trạng thái, mới nhất trước. */
    PageResponse<LoanRescheduleResponse> listAdmin(LoanRescheduleStatus status, int page, int size);

    /**
     * Như trên, thêm ô tìm {@code q}: mã khoản vay {@code LN-…}, mã hồ sơ hoặc mã hợp đồng (mới lẫn cũ), khớp một phần;
     * rỗng thì như trên.
     */
    PageResponse<LoanRescheduleResponse> listAdmin(LoanRescheduleStatus status, String q, int page, int size);

    /**
     * Yêu cầu cơ cấu của đúng một khoản vay cho quản trị (phần "Sau giải ngân" của trang hồ sơ vay), mới nhất trước.
     *
     * @param loanNumber mã khoản vay {@code LN-…}
     * @param status trạng thái cần lọc; {@code null} là mọi trạng thái
     */
    PageResponse<LoanRescheduleResponse> listAdminByLoan(String loanNumber, LoanRescheduleStatus status,
            int page, int size);

    /** Quản trị duyệt: yêu cầu chuyển sang chờ nhà đầu tư đồng ý; gửi lại cùng {@code decisionKey} trả kết quả cũ. */
    LoanRescheduleResponse approve(UUID requestId, String decisionKey,
            AdminLoanRescheduleDecisionRequest input);

    /** Quản trị từ chối, bắt buộc có lý do; gửi lại cùng {@code decisionKey} trả kết quả cũ. */
    LoanRescheduleResponse reject(UUID requestId, String decisionKey,
            AdminLoanRescheduleDecisionRequest input);

    /** Quản trị chạy lại yêu cầu đã hết retry; marker Fineract đảm bảo không tạo trùng. */
    LoanRescheduleResponse retryManual(UUID requestId);

    /** Quản trị đóng yêu cầu lỗi và trả khoản vay về trạng thái trước cơ cấu. */
    LoanRescheduleResponse cancelManual(UUID requestId, String decisionKey,
            AdminLoanRescheduleDecisionRequest input);
}
