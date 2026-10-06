package com.finora.loan.controller;

import com.finora.loan.domain.servicing.RepaymentEventQuarantineStatus;
import com.finora.loan.dto.common.PageResponse;
import com.finora.loan.dto.servicing.response.RepaymentEventQuarantineResponse;
import com.finora.loan.service.servicing.AdminRepaymentEventQuarantineService;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/admin/repayment-event-quarantine")
@RequiredArgsConstructor
@Validated
public class AdminRepaymentEventQuarantineController {
    private final AdminRepaymentEventQuarantineService service;

    @GetMapping
    public PageResponse<RepaymentEventQuarantineResponse> list(
            @RequestParam(required = false) RepaymentEventQuarantineStatus status,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        return service.list(status, page, size);
    }

    @PostMapping("/{eventId}/replay")
    public RepaymentEventQuarantineResponse replay(@PathVariable UUID eventId) {
        return service.replay(eventId);
    }
}
