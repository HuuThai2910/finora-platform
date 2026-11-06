package com.finora.blockchain.service.proof;

import com.fasterxml.jackson.databind.JsonNode;
import com.finora.blockchain.domain.proof.enums.ProofChainCheckStatus;
import com.finora.blockchain.dto.response.ProofVerificationResponse;

import java.util.Optional;

/**
 * Kiểm toàn vẹn proof theo hai chiều: tính lại hash từ artifact nguồn và đối chiếu bản ghi PostgreSQL với Fabric.
 */
public interface ProofVerificationService {

    /**
     * Kiểm một proof: so hash tính lại từ {@code artifact} (bỏ qua khi không gửi) và đối chiếu với Fabric; kết quả
     * đối chiếu được lưu trên proof khi đọc được chain.
     */
    ProofVerificationResponse verify(String proofId, JsonNode artifact);

    /**
     * Worker đối chiếu định kỳ gọi theo ID nội bộ. Trả kết quả đối chiếu; rỗng nếu proof không có hoặc không đối chiếu
     * được (MOCK, chưa xác nhận, ledger không đọc được).
     */
    Optional<ProofChainCheckStatus> reconcile(Long databaseId);
}
