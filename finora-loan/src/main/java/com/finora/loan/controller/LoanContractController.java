package com.finora.loan.controller;

import com.finora.common.security.pin.PinScope;
import com.finora.common.security.pin.RequirePin;
import com.finora.loan.dto.contract.request.DeclineLoanContractRequest;
import com.finora.loan.dto.contract.request.SignLoanContractRequest;
import com.finora.loan.dto.contract.response.LoanContractActionResponse;
import com.finora.loan.dto.contract.response.LoanContractDetailResponse;
import com.finora.loan.dto.contract.response.LoanContractHistoryResponse;
import com.finora.loan.dto.contract.response.LoanContractSummaryResponse;
import com.finora.loan.dto.response.PageResponse;
import com.finora.loan.service.contract.LoanContractPdfContent;
import com.finora.loan.service.contract.LoanContractService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/loan-contracts")
@RequiredArgsConstructor
@Validated
public class LoanContractController {

    private final LoanContractService loanContractService;

    @GetMapping("/me")
    public ResponseEntity<PageResponse<LoanContractSummaryResponse>> listMine(
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size
    ) {
        return ResponseEntity.ok(loanContractService.listMine(page, size));
    }

    @GetMapping("/{contractNumber}")
    public ResponseEntity<LoanContractDetailResponse> detail(
            @PathVariable @NotBlank @Size(max = 50) String contractNumber
    ) {
        return ResponseEntity.ok(loanContractService.detail(contractNumber));
    }

    @GetMapping("/{contractNumber}/document")
    public ResponseEntity<byte[]> document(
            @PathVariable @NotBlank @Size(max = 50) String contractNumber
    ) {
        return pdf(loanContractService.document(contractNumber));
    }

    /** Bản PDF mới nhất của chuỗi chữ ký PAdES; 404 khi chưa bên nào ký số. */
    @GetMapping("/{contractNumber}/signed-document")
    public ResponseEntity<byte[]> signedDocument(
            @PathVariable @NotBlank @Size(max = 50) String contractNumber
    ) {
        return pdf(loanContractService.signedDocument(contractNumber));
    }

    /** Trang biên nhận chữ ký số; 404 khi hợp đồng chưa đủ chữ ký. */
    @GetMapping("/{contractNumber}/receipt")
    public ResponseEntity<byte[]> receipt(
            @PathVariable @NotBlank @Size(max = 50) String contractNumber
    ) {
        return pdf(loanContractService.receipt(contractNumber));
    }

    private ResponseEntity<byte[]> pdf(LoanContractPdfContent document) {
        byte[] content = document.content();
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "inline; filename=\"" + document.fileName() + "\"")
                .header(HttpHeaders.ETAG, "\"" + document.contentHash() + "\"")
                .cacheControl(CacheControl.noStore())
                .contentType(org.springframework.http.MediaType.parseMediaType(document.contentType()))
                .contentLength(content.length)
                .body(content);
    }

    @GetMapping("/{contractNumber}/history")
    public ResponseEntity<PageResponse<LoanContractHistoryResponse>> history(
            @PathVariable @NotBlank @Size(max = 50) String contractNumber,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size
    ) {
        return ResponseEntity.ok(loanContractService.history(contractNumber, page, size));
    }

    @PostMapping("/{contractNumber}/sign")
    @RequirePin(PinScope.SIGN_CONTRACT)
    public ResponseEntity<LoanContractActionResponse> sign(
            @PathVariable @NotBlank @Size(max = 50) String contractNumber,
            @RequestHeader("Idempotency-Key") @NotBlank @Size(max = 150) String idempotencyKey,
            @Valid @RequestBody SignLoanContractRequest request
    ) {
        return ResponseEntity.ok(loanContractService.sign(contractNumber, idempotencyKey, request));
    }

    @PostMapping("/{contractNumber}/signature/refresh")
    public ResponseEntity<LoanContractActionResponse> refreshSignature(
            @PathVariable @NotBlank @Size(max = 50) String contractNumber
    ) {
        return ResponseEntity.ok(loanContractService.refreshSignature(contractNumber));
    }

    @PostMapping("/{contractNumber}/decline")
    public ResponseEntity<LoanContractActionResponse> decline(
            @PathVariable @NotBlank @Size(max = 50) String contractNumber,
            @RequestHeader("Idempotency-Key") @NotBlank @Size(max = 150) String idempotencyKey,
            @Valid @RequestBody DeclineLoanContractRequest request
    ) {
        return ResponseEntity.ok(loanContractService.decline(contractNumber, idempotencyKey, request));
    }
}
