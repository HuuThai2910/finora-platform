package com.finora.loan.controller;

import com.finora.loan.domain.disbursement.DisbursementSagaStatus;
import com.finora.loan.dto.common.PageResponse;
import com.finora.loan.dto.disbursement.request.AdminDisbursementSagaRequeueRequest;
import com.finora.loan.dto.disbursement.response.AdminDisbursementSagaResponse;
import com.finora.loan.service.disbursement.AdminDisbursementSagaService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

/** Saga giải ngân cần vận hành (R2). {@code /api/v1/admin/**} chỉ cho ADMIN (SecurityConfig). */
@RestController
@RequestMapping("/api/v1/admin/disbursement-sagas")
@RequiredArgsConstructor
@Validated
public class AdminDisbursementSagaController {
    private final AdminDisbursementSagaService service;

    @GetMapping
    public PageResponse<AdminDisbursementSagaResponse> list(
            @RequestParam(required = false) DisbursementSagaStatus status,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        return service.list(status, page, size);
    }

    @PostMapping("/{sagaId}/requeue")
    public AdminDisbursementSagaResponse requeue(@PathVariable UUID sagaId,
            @Valid @RequestBody AdminDisbursementSagaRequeueRequest request) {
        return service.requeue(sagaId, request.note());
    }
}
