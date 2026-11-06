package com.finora.loan.controller;

import com.finora.loan.dto.response.PageResponse;
import com.finora.loan.dto.servicing.response.LoanRepaymentScheduleResponse;
import com.finora.loan.dto.servicing.response.LoanServicingSummaryResponse;
import com.finora.loan.dto.servicing.response.PendingLoanActivationResponse;
import com.finora.loan.service.servicing.LoanServicingQueryService;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/v1/loans")
@RequiredArgsConstructor
@Validated
public class LoanServicingController {

    private final LoanServicingQueryService loanServicingQueryService;

    @GetMapping("/me")
    public ResponseEntity<PageResponse<LoanServicingSummaryResponse>> listMine(
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size
    ) {
        return ResponseEntity.ok(loanServicingQueryService.listMine(page, size));
    }

    @GetMapping("/pending-activation")
    public ResponseEntity<List<PendingLoanActivationResponse>> pendingMine() {
        return ResponseEntity.ok(loanServicingQueryService.pendingMine());
    }

    @GetMapping("/{loanNumber}")
    public ResponseEntity<LoanServicingSummaryResponse> getMine(@PathVariable String loanNumber) {
        return ResponseEntity.ok(loanServicingQueryService.getMine(loanNumber));
    }

    @GetMapping("/{loanNumber}/repayment-schedule")
    public ResponseEntity<LoanRepaymentScheduleResponse> schedule(@PathVariable String loanNumber) {
        return ResponseEntity.ok(loanServicingQueryService.schedule(loanNumber));
    }
}

