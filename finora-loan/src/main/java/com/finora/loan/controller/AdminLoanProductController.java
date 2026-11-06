package com.finora.loan.controller;

import com.finora.loan.domain.product.enums.CoreSyncStatus;
import com.finora.loan.domain.product.enums.LoanProductStatus;
import com.finora.loan.dto.core.response.CoreProductSyncResponse;
import com.finora.loan.dto.product.request.CreateLoanProductRequest;
import com.finora.loan.dto.product.request.UpdateLoanProductRequest;
import com.finora.loan.dto.product.request.VersionedActionRequest;
import com.finora.loan.dto.product.response.LoanProductResponse;
import com.finora.loan.dto.response.PageResponse;
import com.finora.loan.service.core.CoreProductSyncService;
import com.finora.loan.service.product.LoanProductService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/admin/loan-products")
@RequiredArgsConstructor
@Validated
public class AdminLoanProductController {

    private final LoanProductService loanProductService;
    private final CoreProductSyncService coreProductSyncService;

    @GetMapping
    public ResponseEntity<PageResponse<LoanProductResponse>> list(
            @RequestParam(required = false) LoanProductStatus status,
            @RequestParam(required = false) CoreSyncStatus coreSyncStatus,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size
    ) {
        return ResponseEntity.ok(loanProductService.listAdmin(status, coreSyncStatus, page, size));
    }

    @PostMapping
    public ResponseEntity<LoanProductResponse> create(@Valid @RequestBody CreateLoanProductRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(loanProductService.create(request));
    }

    @PutMapping("/{id}")
    public ResponseEntity<LoanProductResponse> update(
            @PathVariable long id,
            @Valid @RequestBody UpdateLoanProductRequest request
    ) {
        return ResponseEntity.ok(loanProductService.update(id, request));
    }

    @PostMapping("/{id}/core-sync")
    public ResponseEntity<CoreProductSyncResponse> synchronize(
            @PathVariable long id,
            @Valid @RequestBody VersionedActionRequest request
    ) {
        return ResponseEntity.ok(coreProductSyncService.synchronize(id, request.version()));
    }

    @PostMapping("/{id}/activate")
    public ResponseEntity<LoanProductResponse> activate(
            @PathVariable long id,
            @Valid @RequestBody VersionedActionRequest request
    ) {
        return ResponseEntity.ok(loanProductService.activate(id, request.version()));
    }

    @PostMapping("/{id}/deactivate")
    public ResponseEntity<LoanProductResponse> deactivate(
            @PathVariable long id,
            @Valid @RequestBody VersionedActionRequest request
    ) {
        return ResponseEntity.ok(loanProductService.deactivate(id, request.version()));
    }

    @PostMapping("/{id}/archive")
    public ResponseEntity<LoanProductResponse> archive(
            @PathVariable long id,
            @Valid @RequestBody VersionedActionRequest request
    ) {
        return ResponseEntity.ok(loanProductService.archive(id, request.version()));
    }

    @GetMapping("/{id}")
    public ResponseEntity<LoanProductResponse> detail(@PathVariable long id) {
        return ResponseEntity.ok(loanProductService.getAdminDetail(id));
    }
}
