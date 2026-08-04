package com.finora.loan.controller;

import com.finora.loan.dto.request.CreateLoanProductRequest;
import com.finora.loan.dto.request.UpdateLoanProductRequest;
import com.finora.loan.dto.request.VersionedActionRequest;
import com.finora.loan.dto.response.CoreProductSyncResponse;
import com.finora.loan.dto.response.LoanProductResponse;
import com.finora.loan.service.CoreProductSyncService;
import com.finora.loan.service.LoanProductService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/admin/loan-products")
@RequiredArgsConstructor
public class AdminLoanProductController {

    private final LoanProductService productService;
    private final CoreProductSyncService syncService;

    @PostMapping
    public ResponseEntity<LoanProductResponse> create(@Valid @RequestBody CreateLoanProductRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(productService.create(request));
    }

    @PutMapping("/{id}")
    public LoanProductResponse update(@PathVariable long id, @Valid @RequestBody UpdateLoanProductRequest request) {
        return productService.update(id, request);
    }

    @PostMapping("/{id}/core-sync")
    public CoreProductSyncResponse synchronize(
            @PathVariable long id,
            @Valid @RequestBody VersionedActionRequest request
    ) {
        return syncService.synchronize(id, request.version());
    }

    @PostMapping("/{id}/activate")
    public LoanProductResponse activate(@PathVariable long id, @Valid @RequestBody VersionedActionRequest request) {
        return productService.activate(id, request.version());
    }

    @PostMapping("/{id}/deactivate")
    public LoanProductResponse deactivate(@PathVariable long id, @Valid @RequestBody VersionedActionRequest request) {
        return productService.deactivate(id, request.version());
    }

    @PostMapping("/{id}/archive")
    public LoanProductResponse archive(@PathVariable long id, @Valid @RequestBody VersionedActionRequest request) {
        return productService.archive(id, request.version());
    }

    @GetMapping("/{id}")
    public LoanProductResponse detail(@PathVariable long id) {
        return productService.getAdminDetail(id);
    }
}
