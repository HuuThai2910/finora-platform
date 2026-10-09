package com.finora.loan.controller;

import com.finora.loan.dto.application.response.InvestorBorrowerProfileResponse;
import com.finora.loan.service.application.InvestorBorrowerProfileService;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Hồ sơ vay nhìn từ phía nhà đầu tư: chỉ đọc, chỉ khoản đã lên sàn, ẩn danh người vay. */
@RestController
@RequestMapping("/api/v1/investor/loan-applications")
@RequiredArgsConstructor
@Validated
public class InvestorLoanApplicationController {

    private final InvestorBorrowerProfileService service;

    @GetMapping("/{applicationNumber}/borrower-profile")
    public InvestorBorrowerProfileResponse borrowerProfile(
            @PathVariable @NotBlank @Size(max = 30) String applicationNumber
    ) {
        return service.profile(applicationNumber);
    }
}
