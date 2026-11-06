package com.finora.loan.controller;

import com.finora.loan.domain.servicing.enums.RepaymentEventQuarantineStatus;
import com.finora.loan.dto.response.PageResponse;
import com.finora.loan.dto.servicing.response.RepaymentEventQuarantineResponse;
import com.finora.loan.service.servicing.AdminRepaymentEventQuarantineService;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/admin/repayment-event-quarantine")
@RequiredArgsConstructor
@Validated
public class AdminRepaymentEventQuarantineController {
    private final AdminRepaymentEventQuarantineService adminRepaymentEventQuarantineService;

    @GetMapping
    public ResponseEntity<PageResponse<RepaymentEventQuarantineResponse>> list(
            @RequestParam(required = false) RepaymentEventQuarantineStatus status,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        return ResponseEntity.ok(adminRepaymentEventQuarantineService.list(status, page, size));
    }

    @PostMapping("/{eventId}/replay")
    public ResponseEntity<RepaymentEventQuarantineResponse> replay(@PathVariable UUID eventId) {
        return ResponseEntity.ok(adminRepaymentEventQuarantineService.replay(eventId));
    }
}
