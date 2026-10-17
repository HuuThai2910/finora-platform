package com.finora.payment.controller;

import com.finora.common.dto.PageResponse;
import com.finora.payment.dto.response.DisbursementReconciliationResponse;
import com.finora.payment.service.disbursement.AdminDisbursementReconciliationService;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Lệnh giải ngân cần vận hành (R2). Quyền ADMIN kiểm trong service như đối soát trả nợ. */
@RestController
@RequestMapping("/api/v1/admin/disbursement-reconciliation")
@RequiredArgsConstructor
@Validated
public class AdminDisbursementReconciliationController {
    private final AdminDisbursementReconciliationService service;

    @GetMapping
    public PageResponse<DisbursementReconciliationResponse> list(
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        return service.list(page, size);
    }

    @PostMapping("/{sagaId}/recheck")
    public DisbursementReconciliationResponse recheck(@PathVariable UUID sagaId) {
        return service.requestRecheck(sagaId);
    }
}
