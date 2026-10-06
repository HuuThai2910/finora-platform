package com.finora.loan.controller;

import com.finora.loan.domain.restructure.LoanRescheduleStatus;
import com.finora.loan.dto.common.PageResponse;
import com.finora.loan.dto.restructure.request.AdminLoanRescheduleDecisionRequest;
import com.finora.loan.dto.restructure.response.LoanRescheduleResponse;
import com.finora.loan.service.restructure.LoanRescheduleService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/admin/loan-reschedule-requests")
@RequiredArgsConstructor
@Validated
public class AdminLoanRescheduleController {
    private final LoanRescheduleService service;

    @GetMapping
    public PageResponse<LoanRescheduleResponse> list(
            @RequestParam(required = false) LoanRescheduleStatus status,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        return service.listAdmin(status, page, size);
    }

    @PostMapping("/{requestId}/approve")
    public LoanRescheduleResponse approve(
            @PathVariable UUID requestId,
            @RequestHeader("Idempotency-Key") @NotBlank @Size(max = 150) String idempotencyKey,
            @Valid @RequestBody AdminLoanRescheduleDecisionRequest request) {
        return service.approve(requestId, idempotencyKey, request);
    }

    @PostMapping("/{requestId}/reject")
    public LoanRescheduleResponse reject(
            @PathVariable UUID requestId,
            @RequestHeader("Idempotency-Key") @NotBlank @Size(max = 150) String idempotencyKey,
            @Valid @RequestBody AdminLoanRescheduleDecisionRequest request) {
        return service.reject(requestId, idempotencyKey, request);
    }
}
