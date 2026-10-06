package com.finora.loan.controller;

import com.finora.loan.domain.collection.CollectionCaseStatus;
import com.finora.loan.domain.collection.CollectionStage;
import com.finora.loan.dto.collection.request.CreateCollectionActionRequest;
import com.finora.loan.dto.collection.response.CollectionActionResponse;
import com.finora.loan.dto.collection.response.CollectionCaseResponse;
import com.finora.loan.dto.common.PageResponse;
import com.finora.loan.service.collection.AdminCollectionService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/admin/collection-cases")
@RequiredArgsConstructor
@Validated
public class AdminCollectionController {
    private final AdminCollectionService service;

    @GetMapping
    public PageResponse<CollectionCaseResponse> list(
            @RequestParam(required = false) CollectionCaseStatus status,
            @RequestParam(required = false) CollectionStage stage,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        return service.list(status, stage, page, size);
    }

    @GetMapping("/{caseId}/actions")
    public PageResponse<CollectionActionResponse> actions(@PathVariable UUID caseId,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        return service.actions(caseId, page, size);
    }

    @PostMapping("/{caseId}/actions")
    public CollectionActionResponse record(@PathVariable UUID caseId,
            @RequestHeader("Idempotency-Key") @NotBlank @Size(max = 150) String idempotencyKey,
            @Valid @RequestBody CreateCollectionActionRequest request) {
        return service.record(caseId, idempotencyKey, request);
    }
}
