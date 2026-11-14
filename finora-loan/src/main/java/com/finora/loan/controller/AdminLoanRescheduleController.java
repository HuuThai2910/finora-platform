package com.finora.loan.controller;

import com.finora.loan.domain.restructure.enums.LoanRescheduleStatus;
import com.finora.loan.domain.restructure.enums.LoanRescheduleType;
import com.finora.loan.dto.response.PageResponse;
import com.finora.loan.dto.restructure.request.AdminLoanRescheduleDecisionRequest;
import com.finora.loan.dto.restructure.response.LoanRescheduleResponse;
import com.finora.loan.service.restructure.LoanRescheduleService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/admin/loan-reschedule-requests")
@RequiredArgsConstructor
@Validated
public class AdminLoanRescheduleController {
    private final LoanRescheduleService loanRescheduleService;

    @GetMapping
    public ResponseEntity<PageResponse<LoanRescheduleResponse>> list(
            @RequestParam(required = false) LoanRescheduleStatus status,
            @RequestParam(required = false) List<LoanRescheduleStatus> statuses,
            @RequestParam(required = false) LoanRescheduleType requestType,
            @RequestParam(required = false) @Size(max = 100) String q,
            @RequestParam(required = false) @Size(max = 50) String loanNumber,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        List<LoanRescheduleStatus> requestedStatuses = statuses == null
                ? new ArrayList<>() : new ArrayList<>(statuses);
        // `status` là contract cũ của card chi tiết; `statuses` phục vụ hàng đợi quản trị theo nhóm trạng thái.
        if (status != null && !requestedStatuses.contains(status)) {
            requestedStatuses.add(status);
        }
        return ResponseEntity.ok(loanRescheduleService.listAdmin(
                requestedStatuses, requestType, q, loanNumber, page, size));
    }

    @PostMapping("/{requestId}/approve")
    public ResponseEntity<LoanRescheduleResponse> approve(
            @PathVariable UUID requestId,
            @RequestHeader("Idempotency-Key") @NotBlank @Size(max = 150) String idempotencyKey,
            @Valid @RequestBody AdminLoanRescheduleDecisionRequest request) {
        return ResponseEntity.ok(loanRescheduleService.approve(requestId, idempotencyKey, request));
    }

    @PostMapping("/{requestId}/reject")
    public ResponseEntity<LoanRescheduleResponse> reject(
            @PathVariable UUID requestId,
            @RequestHeader("Idempotency-Key") @NotBlank @Size(max = 150) String idempotencyKey,
            @Valid @RequestBody AdminLoanRescheduleDecisionRequest request) {
        return ResponseEntity.ok(loanRescheduleService.reject(requestId, idempotencyKey, request));
    }

    @PostMapping("/{requestId}/retry")
    public ResponseEntity<LoanRescheduleResponse> retry(@PathVariable UUID requestId) {
        return ResponseEntity.ok(loanRescheduleService.retryManual(requestId));
    }

    @PostMapping("/{requestId}/cancel")
    public ResponseEntity<LoanRescheduleResponse> cancel(
            @PathVariable UUID requestId,
            @RequestHeader("Idempotency-Key") @NotBlank @Size(max = 150) String idempotencyKey,
            @Valid @RequestBody AdminLoanRescheduleDecisionRequest request) {
        return ResponseEntity.ok(loanRescheduleService.cancelManual(requestId, idempotencyKey, request));
    }
}
