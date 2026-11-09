package com.finora.payment.controller;

import com.finora.common.dto.PageResponse;
import com.finora.payment.dto.response.DisbursementReconciliationResponse;
import com.finora.payment.service.disbursement.AdminDisbursementReconciliationService;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/** Lệnh giải ngân cần vận hành (R2). Quyền ADMIN kiểm trong service như đối soát trả nợ. */
@RestController
@RequestMapping("/api/v1/admin/disbursement-reconciliation")
@RequiredArgsConstructor
@Validated
public class AdminDisbursementReconciliationController {
    private final AdminDisbursementReconciliationService adminDisbursementReconciliationService;

    @GetMapping
    public ResponseEntity<PageResponse<DisbursementReconciliationResponse>> list(
            @RequestParam(required = false) @Min(1) Long loanApplicationId,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        // loanApplicationId: phần "Sau giải ngân" của trang hồ sơ vay chỉ đọc lệnh của đúng hồ sơ đó.
        return ResponseEntity.ok(adminDisbursementReconciliationService.list(loanApplicationId, page, size));
    }

    @GetMapping("/{sagaId}")
    public ResponseEntity<DisbursementReconciliationResponse> find(@PathVariable UUID sagaId) {
        return ResponseEntity.ok(adminDisbursementReconciliationService.find(sagaId));
    }

    @PostMapping("/{sagaId}/recheck")
    public ResponseEntity<DisbursementReconciliationResponse> recheck(@PathVariable UUID sagaId) {
        return ResponseEntity.ok(adminDisbursementReconciliationService.requestRecheck(sagaId));
    }
}
