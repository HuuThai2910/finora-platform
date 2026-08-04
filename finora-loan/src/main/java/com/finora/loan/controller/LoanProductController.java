package com.finora.loan.controller;

import com.finora.loan.dto.response.LoanProductCatalogResponse;
import com.finora.loan.dto.response.PageResponse;
import com.finora.loan.service.LoanProductService;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.RequiredArgsConstructor;
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

    private final LoanProductService service;

    @GetMapping("/{id}")
    public LoanProductCatalogResponse activeDetail(@PathVariable long id) {
        return service.getActive(id);
    }

    @GetMapping
    public PageResponse<LoanProductCatalogResponse> activeProducts(
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size
    ) {
        return service.listActive(page, size);
    }
}
