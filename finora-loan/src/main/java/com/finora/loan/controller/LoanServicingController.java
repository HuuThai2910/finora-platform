package com.finora.loan.controller;

import com.finora.loan.dto.common.PageResponse;
import com.finora.loan.dto.servicing.response.LoanRepaymentScheduleResponse;
import com.finora.loan.dto.servicing.response.LoanServicingSummaryResponse;
import com.finora.loan.service.servicing.LoanServicingQueryService;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.RequiredArgsConstructor;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/loans")
@RequiredArgsConstructor
@Validated
public class LoanServicingController {

    private final LoanServicingQueryService service;

    @GetMapping("/me")
    public PageResponse<LoanServicingSummaryResponse> listMine(
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size
    ) {
        return service.listMine(page, size);
    }

    @GetMapping("/{loanNumber}")
    public LoanServicingSummaryResponse getMine(@PathVariable String loanNumber) {
        return service.getMine(loanNumber);
    }

    @GetMapping("/{loanNumber}/repayment-schedule")
    public LoanRepaymentScheduleResponse schedule(@PathVariable String loanNumber) {
        return service.schedule(loanNumber);
    }
}

