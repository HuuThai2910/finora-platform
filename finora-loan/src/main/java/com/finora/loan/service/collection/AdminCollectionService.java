package com.finora.loan.service.collection;

import com.finora.loan.domain.collection.enums.CollectionCaseStatus;
import com.finora.loan.domain.collection.enums.CollectionStage;
import com.finora.loan.dto.collection.request.CreateCollectionActionRequest;
import com.finora.loan.dto.collection.response.CollectionActionResponse;
import com.finora.loan.dto.collection.response.CollectionCaseResponse;
import com.finora.loan.dto.response.PageResponse;

import java.util.UUID;

/**
 * Màn thu hồi nợ của admin: xem hồ sơ thu hồi, lịch sử hành động và ghi hành động thu hồi mới.
 */
public interface AdminCollectionService {

    /** Hồ sơ thu hồi theo trạng thái và giai đoạn ({@code null} là không lọc), DPD cao trước. */
    PageResponse<CollectionCaseResponse> list(CollectionCaseStatus status, CollectionStage stage,
            int page, int size);

    /**
     * Như trên, thêm ô tìm {@code q}: mã khoản vay {@code LN-…}, mã hồ sơ ({@code HS-…}/{@code LA-…}) hoặc mã hợp đồng
     * ({@code HĐ-…}/{@code HD-…}/{@code LC-…}), khớp một phần; rỗng thì như trên.
     */
    PageResponse<CollectionCaseResponse> list(CollectionCaseStatus status, CollectionStage stage, String q,
            int page, int size);

    /**
     * Hồ sơ thu hồi của đúng một khoản vay (phần "Sau giải ngân" của trang hồ sơ vay), mới mở trước.
     *
     * @param loanNumber mã khoản vay {@code LN-…}
     * @param status trạng thái cần lọc; {@code null} là mọi trạng thái
     */
    PageResponse<CollectionCaseResponse> listByLoan(String loanNumber, CollectionCaseStatus status, int page, int size);

    /** Lịch sử hành động của một hồ sơ thu hồi, mới nhất trước. */
    PageResponse<CollectionActionResponse> actions(UUID caseId, int page, int size);

    /**
     * Ghi một hành động thu hồi; cùng {@code Idempotency-Key} và cùng nội dung thì trả lại hành động
     * đã ghi.
     */
    CollectionActionResponse record(UUID caseId, String idempotencyKey,
            CreateCollectionActionRequest request);
}
