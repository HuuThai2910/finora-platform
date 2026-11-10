package com.finora.loan.controller;

import com.finora.loan.dto.contract.response.AdminContractSigningPolicyResponse;
import com.finora.loan.dto.contract.response.AdminLoanContractResponse;
import com.finora.loan.service.contract.AdminLoanContractService;
import com.finora.loan.service.contract.LoanContractPdfContent;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;

/**
 * Hợp đồng vay cho web quản trị (trang Gọi vốn): chỉ đọc. Quyền: Xem "Gọi vốn" hoặc quyền đọc hồ sơ vay, ADMIN luôn qua
 * ({@code SecurityConfig}).
 */
@RestController
@RequestMapping("/api/v1/admin/loan-contracts")
@RequiredArgsConstructor
@Validated
public class AdminLoanContractController {

    private final AdminLoanContractService adminLoanContractService;

    /** Nhà cung cấp chữ ký số và thời hạn ký đang áp dụng, cho khoản vay chưa đủ vốn (chưa có hợp đồng). */
    @GetMapping("/signing-policy")
    public ResponseEntity<AdminContractSigningPolicyResponse> signingPolicy() {
        return ResponseEntity.ok(adminLoanContractService.signingPolicy());
    }

    @GetMapping("/{contractNumber}")
    public ResponseEntity<AdminLoanContractResponse> detail(
            @PathVariable @NotBlank @Size(max = 50) String contractNumber
    ) {
        return ResponseEntity.ok(adminLoanContractService.detail(contractNumber));
    }

    /** PDF hiện hành của hợp đồng; tên tệp theo mã hiển thị {@code HĐ-...} nên mã hóa UTF-8 trong header. */
    @GetMapping("/{contractNumber}/document")
    public ResponseEntity<byte[]> document(
            @PathVariable @NotBlank @Size(max = 50) String contractNumber
    ) {
        LoanContractPdfContent document = adminLoanContractService.document(contractNumber);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                        .filename(document.fileName(), StandardCharsets.UTF_8).build().toString())
                .header(HttpHeaders.ETAG, "\"" + document.contentHash() + "\"")
                .cacheControl(CacheControl.noStore())
                .contentType(MediaType.parseMediaType(document.contentType()))
                .contentLength(document.content().length)
                .body(document.content());
    }
}
