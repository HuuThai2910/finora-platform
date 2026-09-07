package com.finora.loan.service.contract;

import java.util.Arrays;
import java.util.Objects;

/** Bytes PDF đã lưu, không render lại tại thời điểm tải. */
public record LoanContractPdfContent(
        String fileName,
        String contentType,
        String contentHash,
        byte[] content
) {
    public LoanContractPdfContent {
        Objects.requireNonNull(content, "content");
        content = Arrays.copyOf(content, content.length);
    }

    @Override
    public byte[] content() {
        return Arrays.copyOf(content, content.length);
    }
}
