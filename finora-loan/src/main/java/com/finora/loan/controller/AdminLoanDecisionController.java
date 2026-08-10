package com.finora.loan.controller;

import com.finora.loan.domain.application.LoanApplicationStatus;
import com.finora.loan.dto.common.PageResponse;
import com.finora.loan.dto.decision.request.ApproveLoanApplicationRequest;
import com.finora.loan.dto.decision.request.RejectLoanApplicationRequest;
import com.finora.loan.dto.decision.response.AdminLoanDecisionResponse;
import com.finora.loan.dto.decision.response.AdminLoanReviewDetailResponse;
import com.finora.loan.dto.decision.response.AdminLoanReviewSummaryResponse;
import com.finora.loan.service.decision.AdminLoanDecisionService;
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
@RequestMapping("/api/v1/admin/loan-applications")
@RequiredArgsConstructor
@Validated
public class AdminLoanDecisionController {

    private final AdminLoanDecisionService service;

    @GetMapping
    public PageResponse<AdminLoanReviewSummaryResponse> listApplications(
            @RequestParam(required = false) LoanApplicationStatus status,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size
    ) {
        return service.listApplications(status, page, size);
    }

    @GetMapping("/{applicationNumber}/review")
    public AdminLoanReviewDetailResponse reviewDetail(
            @PathVariable @NotBlank @Size(max = 30) String applicationNumber
    ) {
        return service.reviewDetail(applicationNumber);
    }

    @PostMapping("/{applicationNumber}/approve")
    public AdminLoanDecisionResponse approve(
            @PathVariable @NotBlank @Size(max = 30) String applicationNumber,
            @RequestHeader("Idempotency-Key") @NotBlank @Size(max = 150) String idempotencyKey,
            @Valid @RequestBody ApproveLoanApplicationRequest request
    ) {
        return service.approve(applicationNumber, idempotencyKey, request);
    }

    @PostMapping("/{applicationNumber}/reject")
    public AdminLoanDecisionResponse reject(
            @PathVariable @NotBlank @Size(max = 30) String applicationNumber,
            @RequestHeader("Idempotency-Key") @NotBlank @Size(max = 150) String idempotencyKey,
            @Valid @RequestBody RejectLoanApplicationRequest request
    ) {
        return service.reject(applicationNumber, idempotencyKey, request);
    }
}
