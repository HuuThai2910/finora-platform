package com.finora.loan.controller;

import com.finora.loan.domain.collection.enums.CollectionCaseStatus;
import com.finora.loan.domain.collection.enums.CollectionStage;
import com.finora.loan.dto.collection.request.CreateCollectionActionRequest;
import com.finora.loan.dto.collection.response.CollectionActionResponse;
import com.finora.loan.dto.collection.response.CollectionCaseResponse;
import com.finora.loan.dto.response.PageResponse;
import com.finora.loan.service.collection.AdminCollectionService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/admin/collection-cases")
@RequiredArgsConstructor
@Validated
public class AdminCollectionController {
    private final AdminCollectionService adminCollectionService;

    @GetMapping
    public ResponseEntity<PageResponse<CollectionCaseResponse>> list(
            @RequestParam(required = false) CollectionCaseStatus status,
            @RequestParam(required = false) CollectionStage stage,
            @RequestParam(required = false) @Size(max = 100) String q,
            @RequestParam(required = false) @Size(max = 50) String loanNumber,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        // loanNumber: phần "Sau giải ngân" của trang hồ sơ vay chỉ đọc hồ sơ thu hồi của đúng khoản vay đó.
        if (loanNumber != null && !loanNumber.isBlank()) {
            return ResponseEntity.ok(adminCollectionService.listByLoan(loanNumber, status, page, size));
        }
        return ResponseEntity.ok(adminCollectionService.list(status, stage, q, page, size));
    }

    @GetMapping("/{caseId}/actions")
    public ResponseEntity<PageResponse<CollectionActionResponse>> actions(@PathVariable UUID caseId,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        return ResponseEntity.ok(adminCollectionService.actions(caseId, page, size));
    }

    @PostMapping("/{caseId}/actions")
    public ResponseEntity<CollectionActionResponse> record(@PathVariable UUID caseId,
            @RequestHeader("Idempotency-Key") @NotBlank @Size(max = 150) String idempotencyKey,
            @Valid @RequestBody CreateCollectionActionRequest request) {
        return ResponseEntity.ok(adminCollectionService.record(caseId, idempotencyKey, request));
    }
}
