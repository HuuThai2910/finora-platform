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

    /**
     * Payment báo lỗi cuối cùng. Chỉ hợp lệ khi saga còn chờ Payment: lúc đó Payment đã trả hold về {@code HELD}, chưa
     * capture. Đã {@code PAYMENT_FAILED}/{@code COMPLETED} là event lặp hoặc đến muộn nên bỏ qua; mọi trạng thái khác
     * (đã nhận tiền, đang ghi core) là dữ liệu mâu thuẫn và bị từ chối để vào DLT cho người đối soát.
     *
     * @return {@code true} khi saga vừa chuyển sang {@code PAYMENT_FAILED}
     */
    public boolean paymentFailed(String code, String detail, Instant now) {
        if (status == DisbursementSagaStatus.COMPLETED || status == DisbursementSagaStatus.PAYMENT_FAILED) return false;
        if (status != DisbursementSagaStatus.WAITING_PAYMENT) {
            throw new IllegalStateException("DisbursementFailed mâu thuẫn với saga ở trạng thái " + status);
        }
        status = DisbursementSagaStatus.PAYMENT_FAILED;
        lastErrorCode = requireText(code);
        lastErrorDetail = truncate(detail);
        nextRetryAt = null;
        updatedAt = now;
        return true;
    }

    /**
     * Bắt đầu một lần ghi core. Với {@code CORE_BOOKING}, {@code nextRetryAt} là hạn lease: nếu worker chết giữa chừng,
     * hết lease thì worker khác được nhận lại; gateway reconcile theo externalId nên chạy lại không tạo khoản vay thứ hai.
     */
    public void startCoreBooking(Instant now, Instant leaseUntil) {
        if (status != DisbursementSagaStatus.CORE_BOOKING_PENDING && status != DisbursementSagaStatus.RETRY_PENDING
                && !isCoreBookingLeaseExpired(now)) {
            throw new IllegalStateException("Saga chưa sẵn sàng ghi nhận vào core");
        }
        status = DisbursementSagaStatus.CORE_BOOKING;
        attemptCount++;
        nextRetryAt = Objects.requireNonNull(leaseUntil);
        updatedAt = now;
    }

    public boolean isCoreBookingLeaseExpired(Instant now) {
        return status == DisbursementSagaStatus.CORE_BOOKING && nextRetryAt != null && !nextRetryAt.isAfter(now);
    }

    /** Lần ghi core treo quá số lần cho phép: dừng tự động, chờ vận hành đối soát Fineract theo externalId. */
    public void markCoreBookingStalled(Instant now) {
        if (!isCoreBookingLeaseExpired(now)) throw new IllegalStateException("Lease ghi core chưa hết hạn");
        status = DisbursementSagaStatus.REPAIR_REQUIRED;
        lastErrorCode = "CORE_BOOKING_STALLED";
        lastErrorDetail = "Ghi core không trả kết quả sau " + attemptCount + " lần; đối soát Fineract theo externalId";
        nextRetryAt = null;
        updatedAt = now;
    }

    /**
     * Ghi nhận kết quả core của đúng lần thử {@code attempt} (claim token). Kết quả của lần thử cũ đến sau khi lease đã
     * bị nhận lại thì bị bỏ qua.
     *
     * @return {@code false} khi kết quả không còn thuộc lần thử đang chạy
     */
    public boolean complete(int attempt, Long coreLoanId, Instant completedAt, Instant now) {
        if (status != DisbursementSagaStatus.CORE_BOOKING || attemptCount != attempt) return false;
        fineractLoanId = Objects.requireNonNull(coreLoanId);
        disbursedAt = Objects.requireNonNull(completedAt);
        status = DisbursementSagaStatus.COMPLETED;
        lastErrorCode = null;
        lastErrorDetail = null;
        nextRetryAt = null;
        updatedAt = now;
        return true;
    }

    /** @return {@code false} khi kết quả lỗi không còn thuộc lần thử đang chạy */
    public boolean failCore(int attempt, String code, String detail, boolean retryable, Instant retryAt, Instant now) {
        if (status != DisbursementSagaStatus.CORE_BOOKING || attemptCount != attempt) return false;
        status = retryable ? DisbursementSagaStatus.RETRY_PENDING : DisbursementSagaStatus.REPAIR_REQUIRED;
        lastErrorCode = requireText(code);
        lastErrorDetail = truncate(detail);
        nextRetryAt = retryable ? Objects.requireNonNull(retryAt) : null;
        updatedAt = now;
        return true;
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

