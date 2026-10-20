package com.finora.blockchain.controller;

import com.finora.blockchain.dto.request.ProofVerificationRequest;
import com.finora.blockchain.dto.response.ProofResponse;
import com.finora.blockchain.dto.response.ProofVerificationResponse;
import com.finora.blockchain.service.proof.ProofQueryService;
import com.finora.blockchain.service.proof.ProofSubmissionService;
import com.finora.blockchain.service.proof.ProofVerificationService;
import com.finora.common.dto.PageResponse;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Explorer và kiểm toàn vẹn proof (R6, W3D D54). Chỉ ADMIN (cấu hình ở {@code BlockchainSecurityConfig}); qua Gateway
 * route {@code /api/v1/blockchain/**}. Ví dụ: {@code GET ?referenceType=LOAN_APPLICATION&referenceId=123}.
 */
@RestController
@RequestMapping("/api/v1/blockchain/proofs")
@RequiredArgsConstructor
@Validated
public class ProofController {

    private final ProofQueryService queryService;
    private final ProofVerificationService verificationService;
    private final ProofSubmissionService submissionService;

    @GetMapping
    public PageResponse<ProofResponse> search(
            @RequestParam(required = false) @Size(max = 40) String referenceType,
            @RequestParam(required = false) @Size(max = 100) String referenceId,
            @RequestParam(required = false) @Size(max = 30) String status,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size
    ) {
        return queryService.search(referenceType, referenceId, status, page, size);
    }

    @GetMapping("/{proofId}")
    public ProofResponse find(@PathVariable String proofId) {
        return queryService.find(proofId);
    }

    /** Đối chiếu DB ↔ Fabric; gửi kèm {@code artifact} (data của event nguồn) để kiểm luôn hash tính lại. */
    @PostMapping("/{proofId}/verify")
    public ProofVerificationResponse verify(
            @PathVariable String proofId,
            @RequestBody(required = false) ProofVerificationRequest request
    ) {
        return verificationService.verify(proofId, request == null ? null : request.artifact());
    }

    /** Đưa proof DEAD_LETTER về hàng đợi sau khi vận hành đã sửa nguyên nhân. */
    @PostMapping("/{proofId}/retry")
    public ProofResponse retry(@PathVariable String proofId) {
        submissionService.requeue(proofId);
        return queryService.find(proofId);
    }
}
