package com.finora.payment.domain.topup;

import com.finora.payment.domain.wallet.PaymentWallet;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** Yêu cầu nạp tiền; chỉ callback/provider mock hợp lệ mới được ghi CREDIT vào wallet. */
@Entity
@Table(name = "payment_top_ups")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class PaymentTopUp {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "top_up_id", nullable = false, unique = true, updatable = false)
    private UUID topUpId;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "wallet_id", nullable = false, updatable = false)
    private PaymentWallet wallet;

    @Column(name = "owner_id", nullable = false, length = 100, updatable = false)
    private String ownerId;

    @Column(nullable = false, precision = 19, scale = 2, updatable = false)
    private BigDecimal amount;

    @Column(nullable = false, length = 3, updatable = false)
    private String currency;

    @Column(nullable = false, length = 30, updatable = false)
    private String provider;

    @Column(name = "provider_order_id", nullable = false, length = 100, updatable = false)
    private String providerOrderId;

    @Column(name = "provider_reference", length = 100)
    private String providerReference;

    @Column(name = "checkout_url", columnDefinition = "TEXT")
    private String checkoutUrl;

    @Column(name = "qr_payload", columnDefinition = "TEXT")
    private String qrPayload;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private TopUpStatus status;

    @Column(name = "idempotency_key", nullable = false, unique = true, length = 150, updatable = false)
    private String idempotencyKey;

    @Column(name = "request_hash", nullable = false, length = 64, updatable = false)
    private String requestHash;

    @Column(name = "error_code", length = 80)
    private String errorCode;

    @Column(name = "error_detail", length = 500)
    private String errorDetail;

    @Column(name = "expires_at")
    private Instant expiresAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    @Version
    @Column(nullable = false)
    private Long version;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    public static PaymentTopUp prepare(
            UUID topUpId,
            PaymentWallet wallet,
            BigDecimal amount,
            String provider,
            String providerOrderId,
            String idempotencyKey,
            String requestHash,
            Instant now
    ) {
        PaymentTopUp topUp = new PaymentTopUp();
        topUp.topUpId = Objects.requireNonNull(topUpId, "topUpId");
        topUp.wallet = Objects.requireNonNull(wallet, "wallet");
        topUp.ownerId = wallet.getOwnerId();
        topUp.amount = requireMoney(amount);
        topUp.currency = wallet.getCurrency();
        topUp.provider = requireText(provider, "provider", 30).toUpperCase();
        topUp.providerOrderId = requireText(providerOrderId, "providerOrderId", 100);
        topUp.idempotencyKey = requireText(idempotencyKey, "idempotencyKey", 150);
        topUp.requestHash = requireHash(requestHash);
        topUp.status = TopUpStatus.PROVIDER_PENDING;
        topUp.createdAt = Objects.requireNonNull(now, "now");
        topUp.updatedAt = now;
        return topUp;
    }

    public void awaitingPayment(String checkoutUrl, String qrPayload, Instant expiresAt, Instant now) {
        if (status == TopUpStatus.AWAITING_PAYMENT || status == TopUpStatus.COMPLETED) {
            return;
        }
        requireStatus(TopUpStatus.PROVIDER_PENDING);
        this.checkoutUrl = trimNullable(checkoutUrl, 2_000);
        this.qrPayload = trimNullable(qrPayload, 4_000);
        this.expiresAt = expiresAt;
        this.status = TopUpStatus.AWAITING_PAYMENT;
        this.updatedAt = Objects.requireNonNull(now, "now");
    }

    public boolean complete(String providerReference, Instant now) {
        if (status == TopUpStatus.COMPLETED) {
            if (!Objects.equals(this.providerReference, providerReference)) {
                throw new IllegalStateException("Top-up đã hoàn tất bằng provider reference khác");
            }
            return false;
        }
        if (status != TopUpStatus.AWAITING_PAYMENT && status != TopUpStatus.PROVIDER_PENDING) {
            throw new IllegalStateException("Top-up không còn chờ thanh toán");
        }
        this.providerReference = requireText(providerReference, "providerReference", 100);
        this.status = TopUpStatus.COMPLETED;
        this.completedAt = Objects.requireNonNull(now, "now");
        this.updatedAt = now;
        this.errorCode = null;
        this.errorDetail = null;
        return true;
    }

    public void fail(String code, String detail, boolean uncertain, Instant now) {
        if (status == TopUpStatus.COMPLETED) {
            return;
        }
        this.status = uncertain ? TopUpStatus.RECONCILIATION_REQUIRED : TopUpStatus.FAILED;
        this.errorCode = requireText(code, "errorCode", 80);
        this.errorDetail = trimNullable(detail, 500);
        this.updatedAt = Objects.requireNonNull(now, "now");
    }

    private void requireStatus(TopUpStatus expected) {
        if (status != expected) {
            throw new IllegalStateException("Top-up không ở trạng thái " + expected);
        }
    }

    private static BigDecimal requireMoney(BigDecimal value) {
        Objects.requireNonNull(value, "amount");
        if (value.signum() <= 0 || value.scale() > 2) {
            throw new IllegalArgumentException("amount phải dương và tối đa 2 chữ số thập phân");
        }
        return value.setScale(2);
    }

    private static String requireHash(String value) {
        String normalized = requireText(value, "requestHash", 64);
        if (!normalized.matches("^[0-9a-f]{64}$")) {
            throw new IllegalArgumentException("requestHash không phải SHA-256 chữ thường");
        }
        return normalized;
    }

    private static String requireText(String value, String field, int max) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " không được để trống");
        }
        String normalized = value.trim();
        if (normalized.length() > max) {
            throw new IllegalArgumentException(field + " vượt quá " + max + " ký tự");
        }
        return normalized;
    }

    private static String trimNullable(String value, int max) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String normalized = value.trim();
        if (normalized.length() > max) {
            throw new IllegalArgumentException("Giá trị provider vượt quá giới hạn");
        }
        return normalized;
    }
}
