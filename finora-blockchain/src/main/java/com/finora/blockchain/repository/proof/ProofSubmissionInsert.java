package com.finora.blockchain.repository.proof;

import com.finora.blockchain.domain.proof.ProofSubmission;

/** Fragment chèn idempotent cho {@link ProofSubmissionRepository}. */
public interface ProofSubmissionInsert {

    /**
     * Chèn bản ghi PENDING nếu chưa trùng source event, proofId hoặc mốc nghiệp vụ; trả số dòng đã chèn (0 hoặc 1).
     * Không ném lỗi unique nên transaction PostgreSQL không bị abort và service đọc lại được bản đã có.
     */
    int insertIfAbsent(ProofSubmission candidate);
}
