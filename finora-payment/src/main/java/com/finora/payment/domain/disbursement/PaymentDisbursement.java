package com.finora.payment.domain.disbursement;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "payment_disbursements")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class PaymentDisbursement {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @Column(name="saga_id", nullable=false, unique=true, updatable=false) private UUID sagaId;
    @Column(name="loan_application_id", nullable=false, updatable=false) private Long loanApplicationId;
    @Column(name="contract_number", nullable=false, length=50, updatable=false) private String contractNumber;
    @Column(name="listing_id", nullable=false, updatable=false) private Long listingId;
    @Column(name="borrower_id", nullable=false, length=100, updatable=false) private String borrowerId;
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name="allocation_snapshot_json", columnDefinition="jsonb", updatable=false)
    private String allocationSnapshotJson;
    @Column(nullable=false, precision=18, scale=2, updatable=false) private BigDecimal amount;
    @Column(nullable=false, length=3, updatable=false) private String currency;
    @Enumerated(EnumType.STRING) @Column(nullable=false, length=30) private PaymentDisbursementStatus status;
    @Column(nullable=false, length=30, updatable=false) private String provider;
    @Column(name="provider_reference", unique=true, length=100) private String providerReference;
    @Column(name="ledger_transaction_id") private UUID ledgerTransactionId;
    @Column(name="attempt_count", nullable=false) private int attemptCount;
    @Column(name="reconciliation_attempt_count", nullable=false) private int reconciliationAttemptCount;
    @Column(name="next_attempt_at") private Instant nextAttemptAt;
    @Column(name="error_code", length=80) private String errorCode;
    @Column(name="error_detail", length=500) private String errorDetail;
    @Column(name="completed_at") private Instant completedAt;
    /** Số lần admin yêu cầu tra cứu lại provider sau khi hết lượt tự động (R2, V16). */
    @Column(name="manual_recheck_count", nullable=false) private int manualRecheckCount;
    @Column(name="last_manual_recheck_by", length=100) private String lastManualRecheckBy;
    @Column(name="last_manual_recheck_at") private Instant lastManualRecheckAt;
    @Version @Column(nullable=false) private Long version;
    @Column(name="created_at", nullable=false, updatable=false) private Instant createdAt;
    @Column(name="updated_at", nullable=false) private Instant updatedAt;

    public static PaymentDisbursement requested(UUID sagaId, Long applicationId, String contractNumber,
            Long listingId, String borrowerId, BigDecimal amount, String currency, String provider,
            String allocationSnapshotJson, Instant now) {
        PaymentDisbursement value = new PaymentDisbursement();
        value.sagaId=sagaId; value.loanApplicationId=applicationId; value.contractNumber=contractNumber;
        value.listingId=listingId; value.borrowerId=borrowerId; value.amount=amount.setScale(2);
        value.currency=currency; value.provider=provider; value.allocationSnapshotJson=allocationSnapshotJson;
        value.status=PaymentDisbursementStatus.REQUESTED;
        value.nextAttemptAt=now; value.createdAt=now; value.updatedAt=now;
        return value;
    }
    /**
     * Bắt đầu gọi provider. Khi {@code PROCESSING}, {@code nextAttemptAt} là hạn lease: còn {@code PROCESSING} sau hạn
     * này nghĩa là worker đã chết giữa lúc gọi provider và kết quả chuyển tiền chưa được biết.
     */
    public void start(Instant now, Instant leaseUntil) {
        status=PaymentDisbursementStatus.PROCESSING; attemptCount++;
        nextAttemptAt=java.util.Objects.requireNonNull(leaseUntil); updatedAt=now;
    }
    public boolean isProcessingLeaseExpired(Instant now) {
        return status==PaymentDisbursementStatus.PROCESSING && nextAttemptAt!=null && !nextAttemptAt.isAfter(now);
    }
    /** Chỉ lệnh đang gọi provider hoặc đang đối soát mới được ghi kết quả; kết quả đến muộn sau khi đã chốt bị bỏ qua. */
    public boolean acceptsProviderResult() {
        return status==PaymentDisbursementStatus.PROCESSING || status==PaymentDisbursementStatus.RECONCILIATION_REQUIRED;
    }
    public void complete(String reference, UUID ledgerTransactionId, Instant now) {
        providerReference=reference; this.ledgerTransactionId=ledgerTransactionId;
        status=PaymentDisbursementStatus.COMPLETED; completedAt=now; nextAttemptAt=null; updatedAt=now;
    }
    /**
     * Kết quả chuyển tiền chưa chắc chắn: chuyển sang đối soát và hẹn lần tra cứu provider kế tiếp
     * ({@code nextCheckAt = null} là chờ vận hành). Không bao giờ quay lại gọi chuyển tiền từ trạng thái này.
     */
    public void requireReconciliation(String code, String detail, Instant now, Instant nextCheckAt) {
        errorCode=code; errorDetail=detail == null ? null : detail.substring(0, Math.min(detail.length(),500));
        status=PaymentDisbursementStatus.RECONCILIATION_REQUIRED; nextAttemptAt=nextCheckAt; updatedAt=now;
    }
    /** Ghi nhận một lần tra cứu provider chưa có kết quả. */
    public void recordReconciliationCheck(Instant now, Instant nextCheckAt) {
        if (status!=PaymentDisbursementStatus.RECONCILIATION_REQUIRED) throw new IllegalStateException("Lệnh không ở trạng thái đối soát");
        reconciliationAttemptCount++; nextAttemptAt=nextCheckAt; updatedAt=now;
    }
    /**
     * Admin yêu cầu tra cứu lại provider theo sagaId (R2): hẹn lần tra cứu ngay và cấp lại lượt tra cứu tự động. Chỉ
     * nhận lệnh đang đối soát; tuyệt đối không quay về gọi chuyển tiền.
     */
    public void requestManualRecheck(String actorId, Instant now) {
        if (status!=PaymentDisbursementStatus.RECONCILIATION_REQUIRED) throw new IllegalStateException("Lệnh không ở trạng thái đối soát");
        if (actorId==null || actorId.isBlank()) throw new IllegalArgumentException("Thiếu người yêu cầu tra cứu lại");
        reconciliationAttemptCount=0; nextAttemptAt=java.util.Objects.requireNonNull(now);
        manualRecheckCount++; lastManualRecheckBy=actorId.trim(); lastManualRecheckAt=now; updatedAt=now;
    }
    public void fail(String code, String detail, boolean retry, Instant retryAt, Instant now) {
        errorCode=code; errorDetail=detail == null ? null : detail.substring(0, Math.min(detail.length(),500));
        status=retry ? PaymentDisbursementStatus.RETRY_PENDING : PaymentDisbursementStatus.FAILED;
        nextAttemptAt=retry ? retryAt : null; reconciliationAttemptCount=0; updatedAt=now;
    }
}
