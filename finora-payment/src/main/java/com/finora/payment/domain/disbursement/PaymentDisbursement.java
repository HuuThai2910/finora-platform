package com.finora.payment.domain.disbursement;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

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
    @Column(nullable=false, precision=18, scale=2, updatable=false) private BigDecimal amount;
    @Column(nullable=false, length=3, updatable=false) private String currency;
    @Enumerated(EnumType.STRING) @Column(nullable=false, length=30) private PaymentDisbursementStatus status;
    @Column(nullable=false, length=30, updatable=false) private String provider;
    @Column(name="provider_reference", unique=true, length=100) private String providerReference;
    @Column(name="attempt_count", nullable=false) private int attemptCount;
    @Column(name="next_attempt_at") private Instant nextAttemptAt;
    @Column(name="error_code", length=80) private String errorCode;
    @Column(name="error_detail", length=500) private String errorDetail;
    @Column(name="completed_at") private Instant completedAt;
    @Version @Column(nullable=false) private Long version;
    @Column(name="created_at", nullable=false, updatable=false) private Instant createdAt;
    @Column(name="updated_at", nullable=false) private Instant updatedAt;

    public static PaymentDisbursement requested(UUID sagaId, Long applicationId, String contractNumber,
            Long listingId, String borrowerId, BigDecimal amount, String currency, String provider, Instant now) {
        PaymentDisbursement value = new PaymentDisbursement();
        value.sagaId=sagaId; value.loanApplicationId=applicationId; value.contractNumber=contractNumber;
        value.listingId=listingId; value.borrowerId=borrowerId; value.amount=amount.setScale(2);
        value.currency=currency; value.provider=provider; value.status=PaymentDisbursementStatus.REQUESTED;
        value.nextAttemptAt=now; value.createdAt=now; value.updatedAt=now;
        return value;
    }
    public void start(Instant now) { status=PaymentDisbursementStatus.PROCESSING; attemptCount++; nextAttemptAt=null; updatedAt=now; }
    public void complete(String reference, Instant now) { providerReference=reference; status=PaymentDisbursementStatus.COMPLETED; completedAt=now; updatedAt=now; }
    public void fail(String code, String detail, boolean retry, Instant retryAt, Instant now) {
        errorCode=code; errorDetail=detail == null ? null : detail.substring(0, Math.min(detail.length(),500));
        status=retry ? PaymentDisbursementStatus.RETRY_PENDING : PaymentDisbursementStatus.FAILED;
        nextAttemptAt=retry ? retryAt : null; updatedAt=now;
    }
}

