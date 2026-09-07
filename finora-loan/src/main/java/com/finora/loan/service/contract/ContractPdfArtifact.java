package com.finora.loan.service.contract;

import com.finora.loan.domain.contract.ContractPdfArtifactType;
import java.util.Arrays;
import java.util.Objects;

/** Bytes và metadata được tạo cùng lúc; caller phải lưu nguyên vẹn, không render lại khi tải. */
public record ContractPdfArtifact(
        ContractPdfArtifactType artifactType,
        String documentVersion,
        byte[] content,
        String contentHash
) {
    public ContractPdfArtifact {
        Objects.requireNonNull(content, "content");
        content = Arrays.copyOf(content, content.length);
    }

    @Override
    public byte[] content() {
        return Arrays.copyOf(content, content.length);
    }
}
