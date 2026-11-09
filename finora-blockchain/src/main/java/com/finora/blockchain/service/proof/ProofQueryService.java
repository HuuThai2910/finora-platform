package com.finora.blockchain.service.proof;

import com.finora.blockchain.domain.proof.entity.ProofSubmission;
import com.finora.blockchain.dto.response.ProofResponse;
import com.finora.blockchain.repository.proof.ProofSubmissionRepository;
import com.finora.common.dto.PageResponse;
import com.finora.common.exception.BusinessException;
import org.springframework.http.HttpStatus;

import java.util.UUID;

/**
 * Tra cứu proof cho explorer quản trị: liệt kê theo tham chiếu nghiệp vụ, theo trạng thái hoặc toàn bộ, và xem chi
 * tiết một proof.
 */
public interface ProofQueryService {

    /**
     * Một trang proof: theo cặp {@code referenceType + referenceId} (cũ trước), theo {@code status} (mới cập nhật
     * trước) hoặc toàn bộ (mới tạo trước). Tra theo tham chiếu thì không lọc thêm trạng thái.
     */
    PageResponse<ProofResponse> search(String referenceType, String referenceId, String status, int page, int size);

    /**
     * Như trên, thêm bộ lọc của trang Kiểm toán (loại bằng chứng, sổ cái, khoảng ngày tạo) và cho ghép trạng thái với
     * tham chiếu (một câu truy vấn Specification). Bộ lọc rỗng và không ghép thì đúng như bản trên.
     */
    PageResponse<ProofResponse> search(String referenceType, String referenceId, String status,
                                       com.finora.blockchain.dto.request.ProofSearchFilter filter, int page, int size);

    /** Chi tiết một proof; {@code proofId} sai định dạng trả 400, không có proof trả 404. */
    ProofResponse find(String proofId);

    /** Đọc proof theo {@code proofId}; không có thì ném 404 {@code PROOF_NOT_FOUND}. */
    static ProofSubmission load(ProofSubmissionRepository repository, UUID proofId) {
        return repository.findByProofId(proofId).orElseThrow(
                () -> new BusinessException(HttpStatus.NOT_FOUND, "PROOF_NOT_FOUND", "Không tìm thấy proof"));
    }

    /** Đọc {@code proofId} dạng UUID; sai định dạng thì ném 400 {@code PROOF_ID_INVALID}. */
    static UUID parseProofId(String value) {
        try {
            return UUID.fromString(value == null ? "" : value.trim());
        } catch (IllegalArgumentException exception) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "PROOF_ID_INVALID", "proofId phải là UUID");
        }
    }
}
