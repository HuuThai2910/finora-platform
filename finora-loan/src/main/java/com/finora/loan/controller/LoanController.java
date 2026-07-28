package com.finora.loan.controller;

import com.finora.common.dto.BaseResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST API quản lý Hồ sơ vay.
 * TODO: Implement CRUD hồ sơ vay, duyệt hồ sơ, tạo lịch trả nợ.
 */
@RestController
@RequestMapping("/api/v1/loans")
@RequiredArgsConstructor
public class LoanController {

    @GetMapping("/health")
    public BaseResponse<String> healthCheck() {
        return BaseResponse.success("FINORA Loan Service is running! 🏦");
    }
}
