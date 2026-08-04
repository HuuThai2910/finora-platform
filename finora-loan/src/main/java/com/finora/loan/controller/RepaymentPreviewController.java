package com.finora.loan.controller;

import com.finora.loan.dto.request.RepaymentPreviewRequest;
import com.finora.loan.dto.response.RepaymentPreviewResponse;
import com.finora.loan.service.RepaymentPreviewService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/loan-products/{productId}/repayment-previews")
@RequiredArgsConstructor
public class RepaymentPreviewController {

    private final RepaymentPreviewService service;

    @PostMapping
    public RepaymentPreviewResponse preview(
            @PathVariable long productId,
            @Valid @RequestBody RepaymentPreviewRequest request
    ) {
        return service.preview(productId, request);
    }
}
