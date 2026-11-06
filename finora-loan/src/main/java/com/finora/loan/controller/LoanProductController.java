package com.finora.loan.controller;

import com.finora.loan.dto.product.response.LoanProductCatalogResponse;
import com.finora.loan.dto.response.PageResponse;
import com.finora.loan.service.product.LoanProductService;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/loan-products")
@RequiredArgsConstructor
@Validated
public class LoanProductController {

    private final LoanProductService loanProductService;

    @GetMapping("/{id}")
    public ResponseEntity<LoanProductCatalogResponse> activeDetail(@PathVariable long id) {
        return ResponseEntity.ok(loanProductService.getActive(id));
    }

    @GetMapping
    public ResponseEntity<PageResponse<LoanProductCatalogResponse>> activeProducts(
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size
    ) {
        return ResponseEntity.ok(loanProductService.listActive(page, size));
    }
}
