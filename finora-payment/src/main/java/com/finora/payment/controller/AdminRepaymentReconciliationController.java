package com.finora.payment.controller;

import com.finora.common.dto.PageResponse;
import com.finora.payment.domain.repayment.enums.PaymentRepaymentStatus;
import com.finora.payment.dto.response.RepaymentReconciliationResponse;
import com.finora.payment.service.repayment.AdminRepaymentReconciliationService;
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

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/admin/repayment-reconciliation")
@RequiredArgsConstructor
@Validated
public class AdminRepaymentReconciliationController {
    private final AdminRepaymentReconciliationService adminRepaymentReconciliationService;

    @GetMapping
    public ResponseEntity<PageResponse<RepaymentReconciliationResponse>> list(
            @RequestParam(name = "status", required = false) List<PaymentRepaymentStatus> statuses,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        return ResponseEntity.ok(adminRepaymentReconciliationService.list(statuses, page, size));
    }

    @PostMapping("/{repaymentId}/reconcile")
    public ResponseEntity<RepaymentReconciliationResponse> reconcile(@PathVariable UUID repaymentId) {
        return ResponseEntity.ok(adminRepaymentReconciliationService.reconcile(repaymentId));
    }

    /** B32 (D28): ghi lại vào Fineract với đúng externalId sau khi tra cứu xác nhận core chưa có giao dịch. */
    @PostMapping("/{repaymentId}/repost")
    public ResponseEntity<RepaymentReconciliationResponse> repost(@PathVariable UUID repaymentId) {
        return ResponseEntity.ok(adminRepaymentReconciliationService.repost(repaymentId));
    }
}
