package com.finora.investment.controller;

import com.finora.common.dto.PageResponse;
import com.finora.investment.dto.request.InvestorRescheduleDecisionRequest;
import com.finora.investment.dto.response.InvestorRescheduleConsentResponse;
import com.finora.investment.service.InvestorRescheduleConsentService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/investments/reschedule-consents")
@RequiredArgsConstructor
@Validated
public class InvestorRescheduleConsentController {
    private final InvestorRescheduleConsentService service;

    @GetMapping
    public PageResponse<InvestorRescheduleConsentResponse> listMine(
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        return service.listMine(page, size);
    }

    @GetMapping("/{requestId}")
    public InvestorRescheduleConsentResponse detail(@PathVariable UUID requestId) {
        return service.detail(requestId);
    }

    @PostMapping("/{requestId}/approve")
    public InvestorRescheduleConsentResponse approve(@PathVariable UUID requestId,
            @RequestHeader("Idempotency-Key") @NotBlank String idempotencyKey,
            @Valid @RequestBody(required = false) InvestorRescheduleDecisionRequest input) {
        return service.decide(requestId, idempotencyKey, true, input == null ? null : input.comment());
    }

    @PostMapping("/{requestId}/reject")
    public InvestorRescheduleConsentResponse reject(@PathVariable UUID requestId,
            @RequestHeader("Idempotency-Key") @NotBlank String idempotencyKey,
            @Valid @RequestBody(required = false) InvestorRescheduleDecisionRequest input) {
        return service.decide(requestId, idempotencyKey, false, input == null ? null : input.comment());
    }
}
