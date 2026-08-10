package com.finora.loan.controller;

import com.finora.loan.dto.common.PageResponse;
import com.finora.loan.dto.contract.request.DeclineLoanContractRequest;
import com.finora.loan.dto.contract.request.SignLoanContractRequest;
import com.finora.loan.dto.contract.response.LoanContractActionResponse;
import com.finora.loan.dto.contract.response.LoanContractDetailResponse;
import com.finora.loan.dto.contract.response.LoanContractHistoryResponse;
import com.finora.loan.dto.contract.response.LoanContractSummaryResponse;
import com.finora.loan.service.contract.LoanContractService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/loan-contracts")
@RequiredArgsConstructor
@Validated
public class LoanContractController {

    private final LoanContractService service;

    @GetMapping("/me")
    public PageResponse<LoanContractSummaryResponse> listMine(
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size
    ) {
        return service.listMine(page, size);
    }

    @GetMapping("/{contractNumber}")
    public LoanContractDetailResponse detail(
            @PathVariable @NotBlank @Size(max = 50) String contractNumber
    ) {
        return service.detail(contractNumber);
    }

    @GetMapping("/{contractNumber}/history")
    public PageResponse<LoanContractHistoryResponse> history(
            @PathVariable @NotBlank @Size(max = 50) String contractNumber,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size
    ) {
        return service.history(contractNumber, page, size);
    }

    @PostMapping("/{contractNumber}/sign")
    public LoanContractActionResponse sign(
            @PathVariable @NotBlank @Size(max = 50) String contractNumber,
            @RequestHeader("Idempotency-Key") @NotBlank @Size(max = 150) String idempotencyKey,
            @Valid @RequestBody SignLoanContractRequest request
    ) {
        return service.sign(contractNumber, idempotencyKey, request);
    }

    @PostMapping("/{contractNumber}/decline")
    public LoanContractActionResponse decline(
            @PathVariable @NotBlank @Size(max = 50) String contractNumber,
            @RequestHeader("Idempotency-Key") @NotBlank @Size(max = 150) String idempotencyKey,
            @Valid @RequestBody DeclineLoanContractRequest request
    ) {
        return service.decline(contractNumber, idempotencyKey, request);
    }
}
