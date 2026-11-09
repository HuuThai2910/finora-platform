package com.finora.loan.controller;

import com.finora.loan.domain.disbursement.enums.DisbursementSagaStatus;
import com.finora.loan.dto.disbursement.request.AdminDisbursementSagaRequeueRequest;
import com.finora.loan.dto.disbursement.response.AdminDisbursementSagaResponse;
import com.finora.loan.dto.response.PageResponse;
import com.finora.loan.service.disbursement.AdminDisbursementSagaService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

/** Saga giải ngân cần vận hành (R2). Cần quyền "Đối soát & sự cố" (SecurityConfig). */
@RestController
@RequestMapping("/api/v1/admin/disbursement-sagas")
@RequiredArgsConstructor
@Validated
public class AdminDisbursementSagaController {
    private final AdminDisbursementSagaService adminDisbursementSagaService;

    @GetMapping
    public ResponseEntity<PageResponse<AdminDisbursementSagaResponse>> list(
            @RequestParam(required = false) DisbursementSagaStatus status,
            @RequestParam(required = false) @Min(1) Long loanApplicationId,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        // loanApplicationId: phần "Sau giải ngân" của trang hồ sơ vay chỉ đọc saga của đúng hồ sơ đó.
        if (loanApplicationId != null) {
            return ResponseEntity.ok(adminDisbursementSagaService.listByApplication(loanApplicationId, status));
        }
        return ResponseEntity.ok(adminDisbursementSagaService.list(status, page, size));
    }

    @PostMapping("/{sagaId}/requeue")
    public ResponseEntity<AdminDisbursementSagaResponse> requeue(@PathVariable UUID sagaId,
            @Valid @RequestBody AdminDisbursementSagaRequeueRequest request) {
        return ResponseEntity.ok(adminDisbursementSagaService.requeue(sagaId, request.note()));
    }
}
