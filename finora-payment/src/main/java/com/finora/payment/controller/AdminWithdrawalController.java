package com.finora.payment.controller;

import com.finora.common.dto.PageResponse;
import com.finora.payment.dto.response.AdminWithdrawalResponse;
import com.finora.payment.service.withdrawal.AdminWithdrawalService;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Hàng đợi sự cố rút tiền cho ADMIN (R4). Gateway route đường này về Payment trước {@code /api/v1/admin/**} của Loan. */
@RestController
@RequestMapping("/api/v1/admin/withdrawals")
@RequiredArgsConstructor
@Validated
public class AdminWithdrawalController {

    private final AdminWithdrawalService service;

    @GetMapping
    public PageResponse<AdminWithdrawalResponse> list(@RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        return service.list(page, size);
    }

    @GetMapping("/{withdrawalId}")
    public AdminWithdrawalResponse find(@PathVariable UUID withdrawalId) {
        return service.find(withdrawalId);
    }

    @PostMapping("/{withdrawalId}/recheck")
    public AdminWithdrawalResponse recheck(@PathVariable UUID withdrawalId) {
        return service.recheck(withdrawalId);
    }
}
