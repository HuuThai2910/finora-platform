package com.finora.blockchain.service.proof;

/**
 * Đăng ký proof hash-only idempotent theo source event và mốc nghiệp vụ, và đưa proof DEAD_LETTER về hàng đợi.
 */
public interface ProofSubmissionService {

    /**
     * Đăng ký proof cho một mốc nghiệp vụ. Event giao trùng hoặc phát lại cùng nội dung trả bản đã có (mốc đã neo bằng
     * hash scheme cũ cũng giữ bản cũ); nội dung khác proof đã có thì 409.
     */
    ProofSubmissionView register(ProofRegistrationCommand command);

    /** Vận hành đưa proof DEAD_LETTER về hàng đợi sau khi sửa nguyên nhân; trạng thái khác trả 409. */
    ProofSubmissionView requeue(String proofId);
}
