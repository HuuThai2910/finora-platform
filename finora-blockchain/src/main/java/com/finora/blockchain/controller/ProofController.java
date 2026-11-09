package com.finora.blockchain.controller;

import com.finora.blockchain.domain.proof.enums.ProofProviderType;
import com.finora.blockchain.domain.proof.enums.ProofType;
import com.finora.blockchain.dto.request.ProofSearchFilter;
import java.time.LocalDate;
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
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.format.annotation.DateTimeFormat;
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

    private final ProofQueryService proofQueryService;
    private final ProofVerificationService proofVerificationService;
    private final ProofSubmissionService proofSubmissionService;

    @GetMapping
    public ResponseEntity<PageResponse<ProofResponse>> search(
            @RequestParam(required = false) @Size(max = 40) String referenceType,
            @RequestParam(required = false) @Size(max = 100) String referenceId,
            @RequestParam(required = false) @Size(max = 30) String status,
            @RequestParam(required = false) ProofType proofType,
            @RequestParam(required = false) ProofProviderType provider,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate createdFrom,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate createdTo,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size
    ) {
        // Bảng lọc trang Kiểm toán: loại bằng chứng, sổ cái (MOCK/HYPERLEDGER_FABRIC), ngày tạo theo giờ Việt Nam.
        ProofSearchFilter filter = new ProofSearchFilter(proofType, provider, createdFrom, createdTo);
        boolean referenceWithStatus = referenceId != null && !referenceId.isBlank() && status != null && !status.isBlank();
        return ResponseEntity.ok(filter.isEmpty() && !referenceWithStatus
                ? proofQueryService.search(referenceType, referenceId, status, page, size)
                : proofQueryService.search(referenceType, referenceId, status, filter, page, size));
    }

    @GetMapping("/{proofId}")
    public ResponseEntity<ProofResponse> find(@PathVariable String proofId) {
        return ResponseEntity.ok(proofQueryService.find(proofId));
    }

    /** Đối chiếu DB ↔ Fabric; gửi kèm {@code artifact} (data của event nguồn) để kiểm luôn hash tính lại. */
    @PostMapping("/{proofId}/verify")
    public ResponseEntity<ProofVerificationResponse> verify(
            @PathVariable String proofId,
            @RequestBody(required = false) ProofVerificationRequest request
    ) {
        return ResponseEntity.ok(proofVerificationService.verify(proofId, request == null ? null : request.artifact()));
    }

    /** Đưa proof DEAD_LETTER về hàng đợi sau khi vận hành đã sửa nguyên nhân. */
    @PostMapping("/{proofId}/retry")
    public ResponseEntity<ProofResponse> retry(@PathVariable String proofId) {
        proofSubmissionService.requeue(proofId);
        return ResponseEntity.ok(proofQueryService.find(proofId));
    }
}
