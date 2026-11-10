package com.finora.loan.controller;

import com.finora.loan.dto.servicing.response.AdminLoanInstallmentsResponse;
import com.finora.loan.service.servicing.AdminLoanInstallmentService;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Kỳ trả của khoản vay đã giải ngân cho web quản trị (thẻ Trả nợ trang Gọi vốn). Quyền: Xem "Gọi vốn" hoặc quyền đọc hồ
 * sơ vay, ADMIN luôn qua ({@code SecurityConfig}).
 */
@RestController
@RequestMapping("/api/v1/admin/loans")
@RequiredArgsConstructor
@Validated
public class AdminLoanInstallmentController {

    private final AdminLoanInstallmentService adminLoanInstallmentService;

    @GetMapping("/{loanNumber}/installments")
    public ResponseEntity<AdminLoanInstallmentsResponse> installments(
            @PathVariable @NotBlank @Size(max = 50) String loanNumber
    ) {
        return ResponseEntity.ok(adminLoanInstallmentService.installments(loanNumber));
    }
}
