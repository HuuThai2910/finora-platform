package com.finora.payment.domain.withdrawal;

import com.finora.payment.domain.wallet.WalletOwnerType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Objects;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * Một lệnh rút tiền từ ví về tài khoản ngân hàng (R4, W3B). Tiền đã rời ví ngay khi lệnh được tạo (bút toán dự trữ,
 * D41); mọi chuyển trạng thái chỉ đi tới, và {@link #fail} chỉ hợp lệ khi đã có bút toán hoàn.
 */
@Entity
@Table(name = "payment_withdrawals")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class PaymentWithdrawal {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "withdrawal_id", nullable = false, updatable = false)
    private UUID withdrawalId;
    @Column(name = "wallet_id", nullable = false, updatable = false)
    private UUID walletId;
    @Enumerated(EnumType.STRING)
    @Column(name = "owner_type", nullable = false, updatable = false, length = 20)
    private WalletOwnerType ownerType;
    @Column(name = "owner_id", nullable = false, updatable = false, length = 100)
    private String ownerId;
    @Column(nullable = false, updatable = false, precision = 19, scale = 2)
    private BigDecimal amount;
    @Column(name = "fee_amount", nullable = false, updatable = false, precision = 19, scale = 2)
    private BigDecimal feeAmount;
    @Column(nullable = false, updatable = false, length = 3)
    private String currency;
    @Column(name = "business_date", nullable = false, updatable = false)
    private LocalDate businessDate;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private WithdrawalStatus status;
    @Column(name = "bank_code", nullable = false, updatable = false, length = 20)
    private String bankCode;
    @Column(name = "account_number_encrypted", nullable = false, updatable = false, length = 512)
    private String accountNumberEncrypted;
    @Column(name = "account_number_masked", nullable = false, updatable = false, length = 32)
    private String accountNumberMasked;
    @Column(name = "account_holder_name", nullable = false, updatable = false, length = 100)
    private String accountHolderName;
    @Column(name = "idempotency_key", nullable = false, updatable = false, length = 150)
    private String idempotencyKey;
    @Column(name = "request_hash", nullable = false, updatable = false, length = 64)
    private String requestHash;
    @Column(nullable = false, updatable = false, length = 30)
    private String provider;
    @Column(name = "provider_reference", length = 100)
    private String providerReference;
    @Column(name = "reserve_ledger_transaction_id", nullable = false, updatable = false)
    private UUID reserveLedgerTransactionId;
    @Column(name = "refund_ledger_transaction_id")
    private UUID refundLedgerTransactionId;
    @Column(name = "attempt_count", nullable = false)
    private int attemptCount;
    @Column(name = "reconciliation_attempt_count", nullable = false)
    private int reconciliationAttemptCount;
    @Column(name = "next_attempt_at")
    private Instant nextAttemptAt;
    @Column(name = "error_code", length = 80)
    private String errorCode;
    @Column(name = "error_detail", length = 500)
    private String errorDetail;
    @Column(name = "manual_recheck_count", nullable = false)
    private int manualRecheckCount;
    @Column(name = "last_manual_recheck_by", length = 100)
    private String lastManualRecheckBy;
    @Column(name = "last_manual_recheck_at")
    private Instant lastManualRecheckAt;
    @Column(name = "completed_at")
    private Instant completedAt;
    @Version
    @Column(nullable = false)
    private Long version;
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    /** Dữ liệu tài khoản nhận tiền đã chuẩn hóa: số tài khoản chỉ ở dạng mã hóa và dạng che. */
    public record Destination(String bankCode, String accountNumberEncrypted, String accountNumberMasked,
            String accountHolderName) {
    }

    public static PaymentWithdrawal request(UUID withdrawalId, UUID walletId, WalletOwnerType ownerType, String ownerId,
            BigDecimal amount, BigDecimal feeAmount, String currency, LocalDate businessDate, Destination destination,
            String idempotencyKey, String requestHash, String provider, UUID reserveLedgerTransactionId, Instant now) {
        PaymentWithdrawal value = new PaymentWithdrawal();
        value.withdrawalId = Objects.requireNonNull(withdrawalId, "withdrawalId");
        value.walletId = Objects.requireNonNull(walletId, "walletId");
        value.ownerType = Objects.requireNonNull(ownerType, "ownerType");
        value.ownerId = Objects.requireNonNull(ownerId, "ownerId");
        value.amount = positive(amount, "amount");
        value.feeAmount = Objects.requireNonNull(feeAmount, "feeAmount").setScale(2);
        value.currency = Objects.requireNonNull(currency, "currency");
        value.businessDate = Objects.requireNonNull(businessDate, "businessDate");
        value.bankCode = destination.bankCode();
        value.accountNumberEncrypted = destination.accountNumberEncrypted();
        value.accountNumberMasked = destination.accountNumberMasked();
        value.accountHolderName = destination.accountHolderName();
        value.idempotencyKey = Objects.requireNonNull(idempotencyKey, "idempotencyKey");
        value.requestHash = Objects.requireNonNull(requestHash, "requestHash");
        value.provider = Objects.requireNonNull(provider, "provider");
        value.reserveLedgerTransactionId = Objects.requireNonNull(reserveLedgerTransactionId, "reserveLedgerTransactionId");
        value.status = WithdrawalStatus.REQUESTED;
        value.nextAttemptAt = now;
        value.createdAt = now;
        value.updatedAt = now;
        return value;
    }

    /** Tổng tiền đã rời ví: số tiền chuyển cộng phí. */
    public BigDecimal totalDebited() {
        return amount.add(feeAmount);
    }

    public boolean isDue(Instant now) {
        return !status.isFinal() && nextAttemptAt != null && !nextAttemptAt.isAfter(now);
    }

    public boolean isProcessingLeaseExpired(Instant now) {
        return status == WithdrawalStatus.PROCESSING && nextAttemptAt != null && !nextAttemptAt.isAfter(now);
    }

    /** Worker nhận lệnh để gửi provider; lease hết mà còn PROCESSING thì chuyển đối soát, không gửi lại. */
    public void startProcessing(Instant now, Instant leaseUntil) {
        require(WithdrawalStatus.REQUESTED);
        attemptCount++;
        status = WithdrawalStatus.PROCESSING;
        nextAttemptAt = leaseUntil;
        updatedAt = now;
    }

    public void providerAccepted(String reference, Instant now, Instant checkAt) {
        require(WithdrawalStatus.PROCESSING);
        providerReference = Objects.requireNonNull(reference, "reference");
        status = WithdrawalStatus.PROVIDER_PENDING;
        nextAttemptAt = checkAt;
        updatedAt = now;
    }

    /** Callback hoặc tra cứu báo đã chi. Trả {@code false} nếu lệnh đã hoàn tất trước đó (callback lặp). */
    public boolean complete(String reference, Instant now) {
        if (status == WithdrawalStatus.COMPLETED) {
            return false;
        }
        if (status == WithdrawalStatus.FAILED) {
            throw new IllegalStateException("Lệnh rút đã thất bại và hoàn tiền, không thể hoàn tất");
        }
        if (reference != null) {
            providerReference = reference;
        }
        status = WithdrawalStatus.COMPLETED;
        completedAt = now;
        nextAttemptAt = null;
        updatedAt = now;
        return true;
    }

    /** Provider khẳng định không chi; {@code refundLedgerTransactionId} là bút toán hoàn đã ghi trong cùng transaction. */
    public void fail(String code, String detail, UUID refundLedgerTransactionId, Instant now) {
        if (status.isFinal()) {
            throw new IllegalStateException("Lệnh rút đã kết thúc: " + status);
        }
        this.refundLedgerTransactionId = Objects.requireNonNull(refundLedgerTransactionId, "refundLedgerTransactionId");
        errorCode = truncate(code, 80);
        errorDetail = truncate(detail, 500);
        status = WithdrawalStatus.FAILED;
        nextAttemptAt = null;
        updatedAt = now;
    }

    /** Kết quả chưa rõ: chỉ tra cứu theo mã lệnh. {@code nextCheckAt = null} là hết lượt tự động, chờ vận hành. */
    public void requireReconciliation(String code, String detail, Instant now, Instant nextCheckAt) {
        if (status.isFinal()) {
            throw new IllegalStateException("Lệnh rút đã kết thúc: " + status);
        }
        errorCode = truncate(code, 80);
        errorDetail = truncate(detail, 500);
        status = WithdrawalStatus.RECONCILIATION_REQUIRED;
        nextAttemptAt = nextCheckAt;
        updatedAt = now;
    }

    /** Một lần tra cứu chưa ra kết quả cuối (provider còn xử lý hoặc tra cứu lỗi). */
    public void recordPendingCheck(Instant now, Instant nextCheckAt) {
        if (status != WithdrawalStatus.PROVIDER_PENDING && status != WithdrawalStatus.RECONCILIATION_REQUIRED) {
            throw new IllegalStateException("Lệnh rút không chờ tra cứu: " + status);
        }
        reconciliationAttemptCount++;
        nextAttemptAt = nextCheckAt;
        updatedAt = now;
    }

    /** Provider khẳng định chưa từng nhận lệnh: gửi lại với đúng mã lệnh (provider idempotent theo mã, D42). */
    public void resubmit(Instant now) {
        if (status != WithdrawalStatus.RECONCILIATION_REQUIRED && status != WithdrawalStatus.PROVIDER_PENDING) {
            throw new IllegalStateException("Chỉ gửi lại lệnh đang tra cứu");
        }
        status = WithdrawalStatus.REQUESTED;
        providerReference = null;
        nextAttemptAt = now;
        updatedAt = now;
    }

    /** Vận hành yêu cầu tra cứu lại lệnh đã hết lượt tự động. */
    public void requestManualRecheck(String actorId, Instant now) {
        require(WithdrawalStatus.RECONCILIATION_REQUIRED);
        manualRecheckCount++;
        lastManualRecheckBy = actorId;
        lastManualRecheckAt = now;
        reconciliationAttemptCount = 0;
        nextAttemptAt = now;
        updatedAt = now;
    }

    private void require(WithdrawalStatus expected) {
        if (status != expected) {
            throw new IllegalStateException("Lệnh rút đang " + status + ", cần " + expected);
        }
    }

    private static BigDecimal positive(BigDecimal value, String field) {
        if (value == null || value.signum() <= 0) {
            throw new IllegalArgumentException(field + " phải dương");
        }
        return value.setScale(2);
    }

    private static String truncate(String value, int length) {
        return value == null ? null : value.substring(0, Math.min(value.length(), length));
    }
}
