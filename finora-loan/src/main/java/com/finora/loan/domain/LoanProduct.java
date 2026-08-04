package com.finora.loan.domain;

import com.finora.loan.exception.LoanBusinessException;
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

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.Locale;
import java.util.Objects;
import java.util.regex.Pattern;

@Entity
@Table(name = "loan_products")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class LoanProduct {

    private static final Pattern CODE_PATTERN = Pattern.compile("[A-Z][A-Z0-9_]{2,49}");

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 50, updatable = false)
    private String code;

    @Column(nullable = false, length = 150)
    private String name;

    @Column(columnDefinition = "TEXT")
    private String description;

    @Column(name = "min_amount", nullable = false, precision = 18, scale = 2)
    private BigDecimal minAmount;

    @Column(name = "max_amount", nullable = false, precision = 18, scale = 2)
    private BigDecimal maxAmount;

    @JdbcTypeCode(SqlTypes.SMALLINT)
    @Column(name = "min_term_months", nullable = false)
    private Integer minTermMonths;

    @JdbcTypeCode(SqlTypes.SMALLINT)
    @Column(name = "max_term_months", nullable = false)
    private Integer maxTermMonths;

    @Column(name = "annual_interest_rate", nullable = false, precision = 7, scale = 4)
    private BigDecimal annualInterestRate;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "repayment_method", nullable = false, length = 30)
    private RepaymentMethod repaymentMethod;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(nullable = false, length = 20)
    private LoanProductStatus status;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "core_sync_status", nullable = false, length = 20)
    private CoreSyncStatus coreSyncStatus;

    @Column(name = "current_core_mapping_id")
    private Long currentCoreMappingId;

    @Column(name = "configuration_version", nullable = false)
    private Long configurationVersion;

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

    /**
     * Tạo Product ở DRAFT/NOT_SYNCED để borrower chưa thể dùng trước khi core xác nhận cấu hình.
     */
    public static LoanProduct create(
            String code,
            String name,
            String description,
            BigDecimal minAmount,
            BigDecimal maxAmount,
            Integer minTermMonths,
            Integer maxTermMonths,
            BigDecimal annualInterestRate,
            RepaymentMethod repaymentMethod,
            String actorId,
            Instant now
    ) {
        LoanProduct product = new LoanProduct();
        product.code = normalizeAndValidateCode(code);
        product.applyFinancialConfiguration(
                name,
                description,
                minAmount,
                maxAmount,
                minTermMonths,
                maxTermMonths,
                annualInterestRate,
                repaymentMethod
        );
        product.status = LoanProductStatus.DRAFT;
        product.coreSyncStatus = CoreSyncStatus.NOT_SYNCED;
        product.configurationVersion = 1L;
        product.createdBy = requireActor(actorId);
        product.updatedBy = product.createdBy;
        product.createdAt = Objects.requireNonNull(now, "now");
        product.updatedAt = now;
        return product;
    }

    /**
     * Chỉ Product DRAFT chưa có lệnh đồng bộ đang chạy mới được đổi điều khoản tài chính.
     */
    public void updateConfiguration(
            String name,
            String description,
            BigDecimal minAmount,
            BigDecimal maxAmount,
            Integer minTermMonths,
            Integer maxTermMonths,
            BigDecimal annualInterestRate,
            RepaymentMethod repaymentMethod,
            long expectedVersion,
            String actorId,
            Instant now
    ) {
        requireVersion(expectedVersion);
        if (status != LoanProductStatus.DRAFT) {
            throw LoanBusinessException.conflict(
                    "LOAN_PRODUCT_TERMS_LOCKED",
                    "Chỉ sản phẩm nháp mới được thay đổi điều khoản tài chính"
            );
        }
        if (coreSyncStatus == CoreSyncStatus.PENDING) {
            throw LoanBusinessException.conflict(
                    "CORE_PRODUCT_SYNC_IN_PROGRESS",
                    "Không thể sửa sản phẩm khi lệnh đồng bộ core đang xử lý"
            );
        }
        applyFinancialConfiguration(
                name,
                description,
                minAmount,
                maxAmount,
                minTermMonths,
                maxTermMonths,
                annualInterestRate,
                repaymentMethod
        );
        coreSyncStatus = CoreSyncStatus.NOT_SYNCED;
        currentCoreMappingId = null;
        configurationVersion++;
        markUpdated(actorId, now);
    }

    /** Đánh dấu đã tạo durable command trước khi thực hiện HTTP ngoài transaction. */
    public void markCoreSyncPending(long expectedVersion, String actorId, Instant now) {
        requireVersion(expectedVersion);
        if (status != LoanProductStatus.DRAFT) {
            throw LoanBusinessException.conflict(
                    "CORE_PRODUCT_SYNC_NOT_ALLOWED",
                    "Chỉ sản phẩm nháp mới được đồng bộ cấu hình core"
            );
        }
        if (coreSyncStatus == CoreSyncStatus.PENDING) {
            throw LoanBusinessException.conflict(
                    "CORE_PRODUCT_SYNC_IN_PROGRESS",
                    "Sản phẩm đang có lệnh đồng bộ core"
            );
        }
        coreSyncStatus = CoreSyncStatus.PENDING;
        currentCoreMappingId = null;
        markUpdated(actorId, now);
    }

    /** Chỉ adapter Fineract gọi sau khi nhận và kiểm tra resource ID hợp lệ. */
    public void markCoreSynced(Long mappingId, String actorId, Instant now) {
        if (mappingId == null) {
            throw new IllegalArgumentException("mappingId không được để trống");
        }
        coreSyncStatus = CoreSyncStatus.SYNCED;
        currentCoreMappingId = mappingId;
        markUpdated(actorId, now);
    }

    /** Lỗi vĩnh viễn mở lại quyền sửa hoặc yêu cầu đồng bộ bằng một command mới. */
    public void markCoreSyncFailed(String actorId, Instant now) {
        coreSyncStatus = CoreSyncStatus.FAILED;
        currentCoreMappingId = null;
        markUpdated(actorId, now);
    }

    public void activate(long expectedVersion, String actorId, Instant now) {
        requireVersion(expectedVersion);
        if (status != LoanProductStatus.DRAFT && status != LoanProductStatus.INACTIVE) {
            throw invalidTransition(LoanProductStatus.ACTIVE);
        }
        if (coreSyncStatus != CoreSyncStatus.SYNCED || currentCoreMappingId == null) {
            throw LoanBusinessException.conflict(
                    "CORE_PRODUCT_NOT_SYNCED",
                    "Sản phẩm chỉ được kích hoạt sau khi đồng bộ Fineract thành công"
            );
        }
        status = LoanProductStatus.ACTIVE;
        markUpdated(actorId, now);
    }

    public void deactivate(long expectedVersion, String actorId, Instant now) {
        requireVersion(expectedVersion);
        if (status != LoanProductStatus.ACTIVE) {
            throw invalidTransition(LoanProductStatus.INACTIVE);
        }
        status = LoanProductStatus.INACTIVE;
        markUpdated(actorId, now);
    }

    public void archive(long expectedVersion, String actorId, Instant now) {
        requireVersion(expectedVersion);
        if (status != LoanProductStatus.DRAFT && status != LoanProductStatus.INACTIVE) {
            throw invalidTransition(LoanProductStatus.ARCHIVED);
        }
        status = LoanProductStatus.ARCHIVED;
        markUpdated(actorId, now);
    }

    /** Product dùng cho borrower phải vừa ACTIVE vừa có mapping core hiện hành. */
    public void requireAvailable() {
        if (status != LoanProductStatus.ACTIVE || coreSyncStatus != CoreSyncStatus.SYNCED
                || currentCoreMappingId == null) {
            throw LoanBusinessException.conflict(
                    "LOAN_PRODUCT_NOT_AVAILABLE",
                    "Sản phẩm vay hiện không sẵn sàng nhận hồ sơ"
            );
        }
    }

    public void requireRequestedTerms(BigDecimal amount, int termMonths) {
        if (amount == null || amount.compareTo(minAmount) < 0 || amount.compareTo(maxAmount) > 0) {
            throw LoanBusinessException.badRequest(
                    "LOAN_TERMS_OUT_OF_RANGE",
                    "Số tiền yêu cầu nằm ngoài giới hạn của sản phẩm"
            );
        }
        if (termMonths < minTermMonths || termMonths > maxTermMonths) {
            throw LoanBusinessException.badRequest(
                    "LOAN_TERMS_OUT_OF_RANGE",
                    "Kỳ hạn yêu cầu nằm ngoài giới hạn của sản phẩm"
            );
        }
    }

    public void requireVersion(long expectedVersion) {
        long currentVersion = version == null ? 0L : version;
        if (currentVersion != expectedVersion) {
            throw LoanBusinessException.conflict(
                    "LOAN_PRODUCT_VERSION_CONFLICT",
                    "Sản phẩm đã được cập nhật bởi yêu cầu khác"
            );
        }
    }

    private void applyFinancialConfiguration(
            String name,
            String description,
            BigDecimal minAmount,
            BigDecimal maxAmount,
            Integer minTermMonths,
            Integer maxTermMonths,
            BigDecimal annualInterestRate,
            RepaymentMethod repaymentMethod
    ) {
        this.name = requireText(name, "LOAN_PRODUCT_NAME_REQUIRED", "Tên sản phẩm không được để trống");
        if (this.name.length() > 150) {
            throw LoanBusinessException.badRequest("LOAN_PRODUCT_NAME_TOO_LONG", "Tên sản phẩm tối đa 150 ký tự");
        }
        this.description = normalizeOptional(description);
        if (minAmount == null || maxAmount == null || minAmount.signum() <= 0 || maxAmount.compareTo(minAmount) < 0) {
            throw LoanBusinessException.badRequest("INVALID_LOAN_PRODUCT_AMOUNT_RANGE", "Khoảng số tiền sản phẩm không hợp lệ");
        }
        if (minTermMonths == null || maxTermMonths == null || minTermMonths <= 0 || maxTermMonths < minTermMonths) {
            throw LoanBusinessException.badRequest("INVALID_LOAN_PRODUCT_TERM_RANGE", "Khoảng kỳ hạn sản phẩm không hợp lệ");
        }
        if (annualInterestRate == null || annualInterestRate.signum() <= 0) {
            throw LoanBusinessException.badRequest("INVALID_LOAN_PRODUCT_RATE", "Lãi suất năm phải lớn hơn 0");
        }
        this.minAmount = normalizeMoney(minAmount);
        this.maxAmount = normalizeMoney(maxAmount);
        this.minTermMonths = minTermMonths;
        this.maxTermMonths = maxTermMonths;
        this.annualInterestRate = annualInterestRate.setScale(4, RoundingMode.HALF_UP);
        this.repaymentMethod = Objects.requireNonNull(repaymentMethod, "repaymentMethod");
    }

    private void markUpdated(String actorId, Instant now) {
        updatedBy = requireActor(actorId);
        updatedAt = Objects.requireNonNull(now, "now");
    }

    private LoanBusinessException invalidTransition(LoanProductStatus target) {
        return LoanBusinessException.conflict(
                "INVALID_LOAN_PRODUCT_TRANSITION",
                "Không thể chuyển sản phẩm từ " + status + " sang " + target
        );
    }

    private static String normalizeAndValidateCode(String value) {
        String normalized = requireText(value, "LOAN_PRODUCT_CODE_REQUIRED", "Mã sản phẩm không được để trống")
                .toUpperCase(Locale.ROOT);
        if (!CODE_PATTERN.matcher(normalized).matches()) {
            throw LoanBusinessException.badRequest(
                    "INVALID_LOAN_PRODUCT_CODE",
                    "Mã sản phẩm phải là UPPER_SNAKE_CASE và dài từ 3 đến 50 ký tự"
            );
        }
        return normalized;
    }

    private static BigDecimal normalizeMoney(BigDecimal value) {
        return value.setScale(2, RoundingMode.HALF_UP);
    }

    private static String requireText(String value, String code, String message) {
        if (value == null || value.isBlank()) {
            throw LoanBusinessException.badRequest(code, message);
        }
        return value.trim();
    }

    private static String normalizeOptional(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static String requireActor(String actorId) {
        if (actorId == null || actorId.isBlank()) {
            throw new IllegalArgumentException("actorId không được để trống");
        }
        return actorId.trim();
    }
}
