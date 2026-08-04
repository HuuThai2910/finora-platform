package com.finora.loan.controller;

import com.finora.loan.dto.request.ScoringRetryRequest;
import com.finora.loan.dto.response.CreditAssessmentDetailResponse;
import com.finora.loan.dto.response.CreditAssessmentSummaryResponse;
import com.finora.loan.dto.response.PageResponse;
import com.finora.loan.service.CreditScoringAssessmentService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
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
@RequestMapping("/api/v1/admin/loan-applications/{applicationNumber}")
@RequiredArgsConstructor
@Validated
public class AdminCreditScoringController {

    private final CreditScoringAssessmentService service;

    @GetMapping("/assessments")
    public PageResponse<CreditAssessmentSummaryResponse> list(
            @PathVariable String applicationNumber,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size
    ) {
        return service.list(applicationNumber, page, size);
    }

    @GetMapping("/assessments/{assessmentId}")
    public CreditAssessmentDetailResponse detail(
            @PathVariable String applicationNumber,
            @PathVariable @Positive Long assessmentId
    ) {
        return service.detail(applicationNumber, assessmentId);
    }

    @PostMapping("/scoring-retry")
    public CreditAssessmentDetailResponse retry(
            @PathVariable String applicationNumber,
            @RequestHeader("Idempotency-Key") @NotBlank @Size(max = 150) String idempotencyKey,
            @Valid @RequestBody ScoringRetryRequest request
    ) {
        return service.retry(applicationNumber, idempotencyKey, request);
    }
}
