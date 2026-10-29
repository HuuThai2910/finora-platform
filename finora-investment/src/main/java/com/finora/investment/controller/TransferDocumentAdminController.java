package com.finora.investment.controller;

import com.finora.common.dto.PageResponse;
import com.finora.investment.domain.transferdocument.NoteTransferDocumentStatus;
import com.finora.investment.dto.response.TransferDocumentAdminResponse;
import com.finora.investment.service.transferdocument.TransferDocumentAdminService;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Giấy xác nhận chuyển nhượng Note cho quản trị: danh sách, chi tiết kèm artifact để xác minh với Blockchain, tải PDF
 * đã đóng dấu và thử lại văn bản lỗi. Xem cần quyền Xem Chợ thứ cấp, thử lại cần quyền Thao tác (ADMIN luôn qua).
 */
@RestController
@RequestMapping("/api/v1/investments/admin/transfer-documents")
@RequiredArgsConstructor
@Validated
public class TransferDocumentAdminController {

    private final TransferDocumentAdminService adminService;

    /** Mới nhất trước; {@code status} bỏ trống là tất cả. */
    @GetMapping
    public PageResponse<TransferDocumentAdminResponse> list(
            @RequestParam(required = false) NoteTransferDocumentStatus status,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        Page<TransferDocumentAdminResponse> result = adminService.list(status, PageRequest.of(page, size));
        return PageResponse.<TransferDocumentAdminResponse>builder()
                .content(result.getContent())
                .page(result.getNumber())
                .size(result.getSize())
                .totalElements(result.getTotalElements())
                .totalPages(result.getTotalPages())
                .last(result.isLast())
                .build();
    }

    @GetMapping("/{tradeReference}")
    public TransferDocumentAdminResponse detail(@PathVariable String tradeReference) {
        return adminService.detail(tradeReference);
    }

    @GetMapping(value = "/{tradeReference}/pdf", produces = MediaType.APPLICATION_PDF_VALUE)
    public ResponseEntity<byte[]> pdf(@PathVariable String tradeReference) {
        TransferDocumentAdminService.IssuedPdf pdf = adminService.pdf(tradeReference);
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_PDF)
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                        .filename(pdf.documentNumber() + ".pdf").build().toString())
                .body(pdf.content());
    }

    @PostMapping("/{tradeReference}/retry")
    public ResponseEntity<Void> retry(@PathVariable String tradeReference) {
        adminService.retry(tradeReference);
        return ResponseEntity.noContent().build();
    }
}
