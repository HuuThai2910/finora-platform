package com.finora.loan.controller;

import com.finora.loan.dto.common.PageResponse;
import com.finora.loan.dto.servicing.response.AdminLoanServicingReconciliationResponse;
import com.finora.loan.domain.servicing.ReconciliationIncidentStatus;
import com.finora.loan.domain.servicing.ReconciliationIncidentType;
import com.finora.loan.dto.servicing.response.LoanReconciliationIncidentResponse;
import com.finora.loan.service.servicing.AdminLoanServicingReconciliationService;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.RequiredArgsConstructor;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/admin/loan-servicing-reconciliation")
@RequiredArgsConstructor
@Validated
public class AdminLoanServicingReconciliationController {
    private final AdminLoanServicingReconciliationService service;

    @GetMapping
    public PageResponse<AdminLoanServicingReconciliationResponse> listStale(
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        return service.listStale(page, size);
    }

    @PostMapping("/{loanNumber}/reconcile")
    public AdminLoanServicingReconciliationResponse reconcile(@PathVariable String loanNumber) {
        return service.reconcile(loanNumber);
    }

    @GetMapping("/incidents")
    public PageResponse<LoanReconciliationIncidentResponse> incidents(
            @RequestParam(required = false) ReconciliationIncidentStatus status,
            @RequestParam(required = false) ReconciliationIncidentType type,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        return service.listIncidents(status, type, page, size);
    }
}
