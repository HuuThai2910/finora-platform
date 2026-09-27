package com.finora.loan.domain.disbursement;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "loan_disbursement_sagas")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class DisbursementSaga {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "saga_id", nullable = false, unique = true, updatable = false)
    private UUID sagaId;
    @Column(name = "loan_application_id", nullable = false, unique = true, updatable = false)
    private Long loanApplicationId;
    @Column(name = "contract_number", nullable = false, unique = true, length = 50, updatable = false)
    private String contractNumber;
    @Column(name = "listing_id", nullable = false, updatable = false)
    private Long listingId;
    @Column(name = "borrower_id", nullable = false, length = 100, updatable = false)
    private String borrowerId;
    @Column(nullable = false, precision = 18, scale = 2, updatable = false)
    private BigDecimal amount;
    @Column(nullable = false, length = 3, updatable = false)
    private String currency;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 40)
    private DisbursementSagaStatus status;
    @Column(name = "payment_reference", length = 100)
    private String paymentReference;
    @Column(name = "fineract_loan_id")
    private Long fineractLoanId;
    @Column(name = "attempt_count", nullable = false)
    private int attemptCount;
    @Column(name = "next_retry_at")
    private Instant nextRetryAt;
    @Column(name = "last_error_code", length = 80)
    private String lastErrorCode;
    @Column(name = "last_error_detail", length = 500)
    private String lastErrorDetail;
    @Column(name = "disbursed_at")
    private Instant disbursedAt;
    @Version @Column(nullable = false)
    private Long version;
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    public static DisbursementSaga waitingPayment(Long applicationId, String contractNumber, Long listingId,
            String borrowerId, BigDecimal amount, Instant now) {
        DisbursementSaga saga = new DisbursementSaga();
        saga.sagaId = UUID.randomUUID();
        saga.loanApplicationId = Objects.requireNonNull(applicationId);
        saga.contractNumber = requireText(contractNumber);
        saga.listingId = Objects.requireNonNull(listingId);
        saga.borrowerId = requireText(borrowerId);
        saga.amount = Objects.requireNonNull(amount).setScale(2);
        saga.currency = "VND";
        saga.status = DisbursementSagaStatus.WAITING_PAYMENT;
        saga.createdAt = now;
        saga.updatedAt = now;
        return saga;
    }

    public void paymentCompleted(String reference, Instant now) {
        if (status == DisbursementSagaStatus.COMPLETED) return;
        if (status != DisbursementSagaStatus.WAITING_PAYMENT && status != DisbursementSagaStatus.CORE_BOOKING_PENDING) {
            throw new IllegalStateException("Saga không chờ kết quả Payment");
        }
        paymentReference = requireText(reference);
        status = DisbursementSagaStatus.CORE_BOOKING_PENDING;
        nextRetryAt = now;
        updatedAt = now;
    }

    public void paymentFailed(String code, String detail, Instant now) {
        if (status == DisbursementSagaStatus.COMPLETED) return;
        status = DisbursementSagaStatus.PAYMENT_FAILED;
        lastErrorCode = requireText(code);
        lastErrorDetail = truncate(detail);
        nextRetryAt = null;
        updatedAt = now;
    }

    public void startCoreBooking(Instant now) {
        if (status != DisbursementSagaStatus.CORE_BOOKING_PENDING && status != DisbursementSagaStatus.RETRY_PENDING) {
            throw new IllegalStateException("Saga chưa sẵn sàng ghi nhận vào core");
        }
        status = DisbursementSagaStatus.CORE_BOOKING;
        attemptCount++;
        nextRetryAt = null;
        updatedAt = now;
    }

    public void complete(Long coreLoanId, Instant completedAt, Instant now) {
        if (status != DisbursementSagaStatus.CORE_BOOKING) throw new IllegalStateException("Saga không ở bước core");
        fineractLoanId = Objects.requireNonNull(coreLoanId);
        disbursedAt = Objects.requireNonNull(completedAt);
        status = DisbursementSagaStatus.COMPLETED;
        lastErrorCode = null;
        lastErrorDetail = null;
        updatedAt = now;
    }

    public void failCore(String code, String detail, boolean retryable, Instant retryAt, Instant now) {
        if (status != DisbursementSagaStatus.CORE_BOOKING) throw new IllegalStateException("Saga không ở bước core");
        status = retryable ? DisbursementSagaStatus.RETRY_PENDING : DisbursementSagaStatus.REPAIR_REQUIRED;
        lastErrorCode = requireText(code);
        lastErrorDetail = truncate(detail);
        nextRetryAt = retryable ? Objects.requireNonNull(retryAt) : null;
        updatedAt = now;
    }

    private static String requireText(String value) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException("Giá trị bắt buộc bị trống");
        return value.trim();
    }
    private static String truncate(String value) {
        if (value == null) return null;
        return value.length() <= 500 ? value : value.substring(0, 500);
    }
}

