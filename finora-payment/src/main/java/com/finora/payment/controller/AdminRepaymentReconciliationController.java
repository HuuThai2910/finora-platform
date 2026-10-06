package com.finora.payment.controller;

import com.finora.common.dto.PageResponse;
import com.finora.payment.dto.response.RepaymentReconciliationResponse;
import com.finora.payment.service.repayment.AdminRepaymentReconciliationService;
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

@RestController
@RequestMapping("/api/v1/admin/repayment-reconciliation")
@RequiredArgsConstructor
@Validated
public class AdminRepaymentReconciliationController {
    private final AdminRepaymentReconciliationService service;

    @GetMapping
    public PageResponse<RepaymentReconciliationResponse> list(
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        return service.list(page, size);
    }

    @PostMapping("/{repaymentId}/reconcile")
    public RepaymentReconciliationResponse reconcile(@PathVariable UUID repaymentId) {
        return service.reconcile(repaymentId);
    }
}
