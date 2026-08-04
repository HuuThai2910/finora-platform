package com.finora.loan.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.Objects;

@Entity
@Table(name = "fineract_product_mappings")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class FineractProductMapping {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "loan_product_id", nullable = false, updatable = false)
    private Long loanProductId;

    @Column(name = "finora_product_version", nullable = false, updatable = false)
    private Long finoraProductVersion;

    @Column(name = "fineract_product_id", unique = true)
    private Long fineractProductId;

    @Column(name = "external_id", nullable = false, length = 100, unique = true, updatable = false)
    private String externalId;

    @Column(name = "config_version", nullable = false, length = 30, updatable = false)
    private String configVersion;

    @Column(name = "request_hash", nullable = false, length = 64, updatable = false)
    private String requestHash;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(nullable = false, length = 20)
    private FineractMappingStatus status;

    @Column(name = "last_error_code", length = 100)
    private String lastErrorCode;

    @Column(name = "last_error_detail", length = 1000)
    private String lastErrorDetail;

    @Column(name = "synced_at")
    private Instant syncedAt;

    @Version
    @Column(nullable = false)
    private Long version;

    @Column(name = "created_by", nullable = false, length = 100, updatable = false)
    private String createdBy;

    @Column(name = "updated_by", nullable = false, length = 100)
    private String updatedBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    public static FineractProductMapping pending(
            Long loanProductId,
            Long finoraProductVersion,
            String externalId,
            String configVersion,
            String requestHash,
            String actorId,
            Instant now
    ) {
        FineractProductMapping mapping = new FineractProductMapping();
        mapping.loanProductId = Objects.requireNonNull(loanProductId, "loanProductId");
        mapping.finoraProductVersion = Objects.requireNonNull(finoraProductVersion, "finoraProductVersion");
        mapping.externalId = requireText(externalId, "externalId");
        mapping.configVersion = requireText(configVersion, "configVersion");
        mapping.requestHash = requireText(requestHash, "requestHash");
        mapping.status = FineractMappingStatus.PENDING;
        mapping.createdBy = requireText(actorId, "actorId");
        mapping.updatedBy = mapping.createdBy;
        mapping.createdAt = Objects.requireNonNull(now, "now");
        mapping.updatedAt = now;
        return mapping;
    }

    public void markSynced(Long resourceId, String actorId, Instant now) {
        if (resourceId == null || resourceId <= 0) {
            throw new IllegalArgumentException("Fineract resourceId không hợp lệ");
        }
        fineractProductId = resourceId;
        status = FineractMappingStatus.SYNCED;
        lastErrorCode = null;
        lastErrorDetail = null;
        syncedAt = now;
        markUpdated(actorId, now);
    }

    public void markRetryPending(String code, String detail, String actorId, Instant now) {
        status = FineractMappingStatus.PENDING;
        lastErrorCode = normalize(code, 100);
        lastErrorDetail = normalize(detail, 1000);
        markUpdated(actorId, now);
    }

    public void markFailed(String code, String detail, String actorId, Instant now) {
        status = FineractMappingStatus.FAILED;
        lastErrorCode = normalize(code, 100);
        lastErrorDetail = normalize(detail, 1000);
        markUpdated(actorId, now);
    }

    private void markUpdated(String actorId, Instant now) {
        updatedBy = requireText(actorId, "actorId");
        updatedAt = Objects.requireNonNull(now, "now");
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " không được để trống");
        }
        return value.trim();
    }

    private static String normalize(String value, int maxLength) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String normalized = value.trim();
        return normalized.length() <= maxLength ? normalized : normalized.substring(0, maxLength);
    }
}
