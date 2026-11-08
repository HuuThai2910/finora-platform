package com.finora.loan.controller;

import com.finora.loan.dto.response.PageResponse;
import com.finora.loan.dto.restructure.request.CreateLoanRescheduleRequest;
import com.finora.loan.dto.restructure.response.LoanReschedulePolicyResponse;
import com.finora.loan.dto.restructure.response.LoanRescheduleEligibilityResponse;
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

@RestController
@RequestMapping("/api/v1/loans")
@RequiredArgsConstructor
@Validated
public class LoanRescheduleController {
    private final LoanRescheduleService loanRescheduleService;

    @GetMapping("/reschedule-policy")
    public ResponseEntity<LoanReschedulePolicyResponse> policy() {
        return ResponseEntity.ok(loanRescheduleService.policy());
    }

    @GetMapping("/{loanNumber}/reschedule-policy")
    public ResponseEntity<LoanRescheduleEligibilityResponse> eligibility(
            @PathVariable @NotBlank @Size(max = 50) String loanNumber) {
        return ResponseEntity.ok(loanRescheduleService.eligibility(loanNumber));
    }

    @PostMapping("/{loanNumber}/reschedule-requests")
    public ResponseEntity<LoanRescheduleResponse> submit(
            @PathVariable @NotBlank @Size(max = 50) String loanNumber,
            @RequestHeader("Idempotency-Key") @NotBlank @Size(max = 150) String idempotencyKey,
            @Valid @RequestBody CreateLoanRescheduleRequest request) {
        return ResponseEntity.ok(loanRescheduleService.submit(loanNumber, idempotencyKey, request));
    }

    @GetMapping("/{loanNumber}/reschedule-requests")
    public ResponseEntity<PageResponse<LoanRescheduleResponse>> listMine(
            @PathVariable @NotBlank @Size(max = 50) String loanNumber,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        return ResponseEntity.ok(loanRescheduleService.listMine(loanNumber, page, size));
    }
}

