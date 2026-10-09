package com.finora.loan.controller;

import com.finora.common.security.pin.PinScope;
import com.finora.common.security.pin.RequirePin;
import com.finora.loan.dto.contract.request.SignLoanContractRequest;
import com.finora.loan.dto.contract.response.InvestorContractResponse;
import com.finora.loan.service.contract.InvestorContractService;
import com.finora.loan.service.contract.LoanContractPdfContent;
import jakarta.validation.Valid;
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
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/investor/loan-contracts")
@RequiredArgsConstructor
@Validated
public class InvestorLoanContractController {

    private final InvestorContractService service;

    @GetMapping("/me")
    public java.util.List<InvestorContractResponse> listMine() {
        return service.listMine();
    }

    @GetMapping("/{contractNumber}")
    public InvestorContractResponse detail(
            @PathVariable @NotBlank @Size(max = 50) String contractNumber
    ) {
        return service.detail(contractNumber);
    }

    @GetMapping("/{contractNumber}/document")
    public ResponseEntity<byte[]> document(
            @PathVariable @NotBlank @Size(max = 50) String contractNumber
    ) {
        LoanContractPdfContent document = service.document(contractNumber);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "inline; filename=\"" + document.fileName() + "\"")
                .header(HttpHeaders.ETAG, "\"" + document.contentHash() + "\"")
                .cacheControl(CacheControl.noStore())
                .contentType(org.springframework.http.MediaType.parseMediaType(document.contentType()))
                .contentLength(document.content().length)
                .body(document.content());
    }

    @PostMapping("/{contractNumber}/sign")
    @RequirePin(PinScope.SIGN_CONTRACT)
    public InvestorContractResponse sign(
            @PathVariable @NotBlank @Size(max = 50) String contractNumber,
            @RequestHeader("Idempotency-Key") @NotBlank @Size(max = 150) String idempotencyKey,
            @Valid @RequestBody SignLoanContractRequest request
    ) {
        return service.sign(contractNumber, idempotencyKey, request);
    }

    @PostMapping("/{contractNumber}/signature/refresh")
    public InvestorContractResponse refreshSignature(
            @PathVariable @NotBlank @Size(max = 50) String contractNumber
    ) {
        return service.refreshSignature(contractNumber);
    }
}
