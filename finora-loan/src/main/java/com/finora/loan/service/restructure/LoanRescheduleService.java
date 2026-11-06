package com.finora.loan.service.restructure;

import com.finora.loan.domain.restructure.enums.LoanRescheduleStatus;
import com.finora.loan.dto.response.PageResponse;
import com.finora.loan.dto.restructure.request.AdminLoanRescheduleDecisionRequest;
import com.finora.loan.dto.restructure.request.CreateLoanRescheduleRequest;
import com.finora.loan.dto.restructure.response.LoanReschedulePolicyResponse;
import com.finora.loan.dto.restructure.response.LoanRescheduleResponse;

import java.util.UUID;

/**
 * Yêu cầu cơ cấu khoản vay: người vay đọc điều khoản, gửi và xem yêu cầu của mình; quản trị duyệt (chuyển sang chờ nhà
 * đầu tư đồng ý) hoặc từ chối.
 */
public interface LoanRescheduleService {

    /** Điều khoản cơ cấu hiện hành (phiên bản, nội dung, hash) để người vay đọc và xác nhận. */
    LoanReschedulePolicyResponse policy();

    /** Người vay gửi yêu cầu cơ cấu cho khoản vay của mình; gửi lại cùng {@code Idempotency-Key} trả yêu cầu cũ. */
    LoanRescheduleResponse submit(String loanNumber, String idempotencyKey,
            CreateLoanRescheduleRequest input);

    /** Yêu cầu cơ cấu của người vay hiện tại cho một khoản vay, mới nhất trước. */
    PageResponse<LoanRescheduleResponse> listMine(String loanNumber, int page, int size);

    /** Danh sách yêu cầu cho quản trị, lọc tùy chọn theo trạng thái, mới nhất trước. */
    PageResponse<LoanRescheduleResponse> listAdmin(LoanRescheduleStatus status, int page, int size);

    /** Quản trị duyệt: yêu cầu chuyển sang chờ nhà đầu tư đồng ý; gửi lại cùng {@code decisionKey} trả kết quả cũ. */
    LoanRescheduleResponse approve(UUID requestId, String decisionKey,
            AdminLoanRescheduleDecisionRequest input);

    /** Quản trị từ chối, bắt buộc có lý do; gửi lại cùng {@code decisionKey} trả kết quả cũ. */
    LoanRescheduleResponse reject(UUID requestId, String decisionKey,
            AdminLoanRescheduleDecisionRequest input);
}
