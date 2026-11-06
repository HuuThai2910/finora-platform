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

    /** Lịch sử hành động của một hồ sơ thu hồi, mới nhất trước. */
    PageResponse<CollectionActionResponse> actions(UUID caseId, int page, int size);

    /**
     * Ghi một hành động thu hồi; cùng {@code Idempotency-Key} và cùng nội dung thì trả lại hành động
     * đã ghi.
     */
    CollectionActionResponse record(UUID caseId, String idempotencyKey,
            CreateCollectionActionRequest request);
}
