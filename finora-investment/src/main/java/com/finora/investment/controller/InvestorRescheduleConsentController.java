package com.finora.investment.controller;

import com.finora.common.dto.PageResponse;
import com.finora.investment.dto.request.InvestorRescheduleDecisionRequest;
import com.finora.investment.dto.response.InvestorRescheduleConsentResponse;
import com.finora.investment.service.InvestorRescheduleConsentService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/investments/reschedule-consents")
@RequiredArgsConstructor
@Validated
public class InvestorRescheduleConsentController {
    private final InvestorRescheduleConsentService investorRescheduleConsentService;

    @GetMapping
    public ResponseEntity<PageResponse<InvestorRescheduleConsentResponse>> listMine(
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        return ResponseEntity.ok(investorRescheduleConsentService.listMine(page, size));
    }

    @GetMapping("/{requestId}")
    public ResponseEntity<InvestorRescheduleConsentResponse> detail(@PathVariable UUID requestId) {
        return ResponseEntity.ok(investorRescheduleConsentService.detail(requestId));
    }

    @PostMapping("/{requestId}/approve")
    public ResponseEntity<InvestorRescheduleConsentResponse> approve(@PathVariable UUID requestId,
            @RequestHeader("Idempotency-Key") @NotBlank String idempotencyKey,
            @Valid @RequestBody(required = false) InvestorRescheduleDecisionRequest input) {
        return ResponseEntity.ok(investorRescheduleConsentService.decide(
                requestId, idempotencyKey, true, input == null ? null : input.comment()));
    }

    @PostMapping("/{requestId}/reject")
    public ResponseEntity<InvestorRescheduleConsentResponse> reject(@PathVariable UUID requestId,
            @RequestHeader("Idempotency-Key") @NotBlank String idempotencyKey,
            @Valid @RequestBody(required = false) InvestorRescheduleDecisionRequest input) {
        return ResponseEntity.ok(investorRescheduleConsentService.decide(
                requestId, idempotencyKey, false, input == null ? null : input.comment()));
    }
}
