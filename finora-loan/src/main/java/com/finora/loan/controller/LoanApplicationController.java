package com.finora.loan.controller;

import com.finora.loan.dto.application.request.CreateLoanApplicationRequest;
import com.finora.loan.dto.application.request.WithdrawLoanApplicationRequest;
import com.finora.loan.dto.application.response.LoanApplicationHistoryResponse;
import com.finora.loan.dto.application.response.LoanApplicationResponse;
import com.finora.loan.dto.common.PageResponse;
import com.finora.loan.service.application.LoanApplicationService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
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
@RequestMapping("/api/v1/loan-applications")
@RequiredArgsConstructor
@Validated
public class LoanApplicationController {

    private final LoanApplicationService service;

    /** Một lần gửi form tạo thẳng hồ sơ SUBMITTED; form dang dở chỉ được giữ ở phía giao diện. */
    @PostMapping
    public ResponseEntity<LoanApplicationResponse> submit(
            @RequestHeader("Idempotency-Key") @NotBlank @Size(max = 150) String idempotencyKey,
            @Valid @RequestBody CreateLoanApplicationRequest request
    ) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.submit(request, idempotencyKey));
    }

    @PostMapping("/{applicationNumber}/withdraw")
    public LoanApplicationResponse withdraw(
            @PathVariable String applicationNumber,
            @Valid @RequestBody WithdrawLoanApplicationRequest request
    ) {
        return service.withdraw(applicationNumber, request);
    }

    @GetMapping("/{applicationNumber}")
    public LoanApplicationResponse getMine(@PathVariable String applicationNumber) {
        return service.getMine(applicationNumber);
    }

    @GetMapping("/me")
    public PageResponse<LoanApplicationResponse> listMine(
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size
    ) {
        return service.listMine(page, size);
    }

    @GetMapping("/{applicationNumber}/history")
    public PageResponse<LoanApplicationHistoryResponse> history(
            @PathVariable String applicationNumber,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size
    ) {
        return service.history(applicationNumber, page, size);
    }
}
