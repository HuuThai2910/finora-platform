package com.finora.loan.controller;

import com.finora.loan.dto.application.response.LoanPurposeResponse;
import com.finora.loan.service.application.LoanApplicationService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import java.util.List;

@RestController
@RequestMapping("/api/v1/loan-purposes")
@RequiredArgsConstructor
public class LoanPurposeController {

    private final LoanApplicationService service;

    @GetMapping
    public List<LoanPurposeResponse> purposes() {
        return service.purposes();
    }
}
