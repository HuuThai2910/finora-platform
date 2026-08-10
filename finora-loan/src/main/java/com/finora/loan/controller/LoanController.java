package com.finora.loan.controller;

import com.finora.common.dto.BaseResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Endpoint kiểm tra tiến trình Loan; health triển khai thực tế dùng Actuator. */
@RestController
@RequestMapping("/api/v1/loans")
@RequiredArgsConstructor
public class LoanController {

    @GetMapping("/health")
    public BaseResponse<String> healthCheck() {
        return BaseResponse.success("FINORA Loan Service is running! 🏦");
    }
}
