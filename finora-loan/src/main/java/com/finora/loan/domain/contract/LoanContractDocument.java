package com.finora.loan.domain.contract;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Lob;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Arrays;
import java.util.Objects;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "loan_contract_documents")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class LoanContractDocument {

    public static final String PDF_CONTENT_TYPE = "application/pdf";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "contract_id", nullable = false, updatable = false)
    private Long contractId;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "artifact_type", nullable = false, length = 30, updatable = false)
    private ContractPdfArtifactType artifactType;

    @Column(name = "document_version", nullable = false, length = 30, updatable = false)
    private String documentVersion;

    @Column(name = "content_type", nullable = false, length = 50, updatable = false)
    private String contentType;

    @Column(name = "content_hash", nullable = false, length = 64, updatable = false)
    private String contentHash;

    @Column(name = "content_length", nullable = false, updatable = false)
    private Integer contentLength;

    @Lob
    @Getter(AccessLevel.NONE)
    @JdbcTypeCode(SqlTypes.BINARY)
    @Column(nullable = false, updatable = false, columnDefinition = "bytea")
    private byte[] content;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    /** Lưu đúng bytes đã băm; bản ghi không có API sửa để tránh thay tài liệu sau khi công bố. */
    public static LoanContractDocument create(
            Long contractId,
            ContractPdfArtifactType artifactType,
            String documentVersion,
            String contentHash,
            byte[] content,
            Instant now
    ) {
        if (content == null || content.length == 0) {
            throw new IllegalArgumentException("PDF hợp đồng không được rỗng");
        }
        LoanContractDocument document = new LoanContractDocument();
        document.contractId = Objects.requireNonNull(contractId, "contractId");
        document.artifactType = Objects.requireNonNull(artifactType, "artifactType");
        document.documentVersion = requireText(documentVersion, "documentVersion");
        document.contentType = PDF_CONTENT_TYPE;
        document.contentHash = requireHash(contentHash);
        document.contentLength = content.length;
        document.content = Arrays.copyOf(content, content.length);
        document.createdAt = Objects.requireNonNull(now, "now");
        document.updatedAt = now;
        return document;
    }

    public byte[] contentCopy() {
        return Arrays.copyOf(content, content.length);
    }

    private static String requireHash(String value) {
        String normalized = requireText(value, "contentHash");
        if (!normalized.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("contentHash phải là SHA-256 hexadecimal chữ thường");
        }
        return normalized;
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " không được để trống");
        }
        return value.trim();
    }
}
