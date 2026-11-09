package com.finora.loan.service.decision;

import com.finora.loan.domain.application.enums.LoanApplicationStatus;
import com.finora.loan.domain.servicing.enums.ApplicationServicingFlag;
import com.finora.loan.dto.decision.request.AdminApplicationFilter;
import com.finora.loan.dto.decision.request.ApproveLoanApplicationRequest;
import com.finora.loan.dto.decision.request.RejectLoanApplicationRequest;
import com.finora.loan.dto.decision.response.AdminAssessmentExplanationResponse;
import com.finora.loan.dto.decision.response.AdminLoanDecisionResponse;
import com.finora.loan.dto.decision.response.AdminLoanReviewDetailResponse;
import com.finora.loan.dto.decision.response.AdminLoanReviewSummaryResponse;
import com.finora.loan.dto.response.PageResponse;

/** Use case dành cho admin đọc evidence và quyết định hồ sơ; AI chỉ cung cấp đầu vào. */
public interface AdminLoanDecisionService {

    /**
     * Không truyền trạng thái nghĩa là xem tất cả hồ sơ, nhưng vẫn chỉ đọc một page có giới hạn.
     * Assessment được tải theo batch để số query không tăng theo số hồ sơ trên trang.
     */
    PageResponse<AdminLoanReviewSummaryResponse> listApplications(LoanApplicationStatus status, int page, int size);

    /** Như trên, lọc thêm theo người vay khi {@code borrowerId} có giá trị. */
    PageResponse<AdminLoanReviewSummaryResponse> listApplications(
            LoanApplicationStatus status, String borrowerId, int page, int size);

    /**
     * Như trên, thêm ô tìm {@code q}: mã hồ sơ ({@code HS-…}/{@code LA-…}), mã hợp đồng ({@code HĐ-…}/{@code HD-…}/
     * {@code LC-…}, khớp một phần, không phân biệt hoa thường) hoặc đúng ID người vay. {@code q} rỗng thì như trên.
     */
    PageResponse<AdminLoanReviewSummaryResponse> listApplications(
            LoanApplicationStatus status, String borrowerId, String q, int page, int size);

    /**
     * Như trên, lọc thêm theo việc vận hành của khoản vay ({@code servicing}): đang quá hạn, chờ duyệt cơ cấu hoặc có sự
     * cố đối soát/giải ngân. {@code servicing} {@code null} thì như trên. Mỗi dòng kèm mã khoản vay và các cờ việc đang
     * mở để danh sách gắn nhãn cảnh báo.
     */
    PageResponse<AdminLoanReviewSummaryResponse> listApplications(
            LoanApplicationStatus status, String borrowerId, String q, ApplicationServicingFlag servicing,
            int page, int size);

    /**
     * Như trên, thêm bộ lọc nâng cao của bảng lọc web (ngày nộp, số tiền, kỳ hạn, điểm/hạng lần chấm mới nhất, lãi
     * suất, sản phẩm, mục đích). Bộ lọc rỗng thì như trên; khoảng sai trả 400 {@code FILTER_RANGE_INVALID} /
     * {@code FILTER_VALUE_INVALID}.
     */
    PageResponse<AdminLoanReviewSummaryResponse> listApplications(
            LoanApplicationStatus status, String borrowerId, String q, ApplicationServicingFlag servicing,
            AdminApplicationFilter filter, int page, int size);

    /**
     * Detail dùng số query cố định và chỉ đọc snapshot local; không gọi User, AI hoặc Fineract.
     * Số hợp đồng lấy bằng một query theo khóa duy nhất {@code application_id}, không tải cả Contract.
     */
    AdminLoanReviewDetailResponse reviewDetail(String applicationNumber);

    /**
     * Phần giải thích của lần chấm điểm đã dùng để quyết định hồ sơ.
     *
     * <p>Trả lại đúng bản AI đã sinh lúc chấm chứ không chấm lại, để màn hình thẩm
     * định là bằng chứng cho quyết định đã ra thay vì một kết quả mới.</p>
     */
    AdminAssessmentExplanationResponse assessmentExplanation(String applicationNumber);

    AdminLoanDecisionResponse approve(
            String applicationNumber,
            String idempotencyKey,
            ApproveLoanApplicationRequest request
    );

    AdminLoanDecisionResponse reject(
            String applicationNumber,
            String idempotencyKey,
            RejectLoanApplicationRequest request
    );
}
