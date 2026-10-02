package com.finora.payment.domain.hold;

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

/** Khoản tiền đã chuyển từ AVAILABLE sang HELD cho một lệnh đầu tư. */
@Entity
@Table(name = "payment_holds")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class PaymentHold {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "hold_id", nullable = false, unique = true, updatable = false)
    private UUID holdId;

    @Column(name = "hold_reference", nullable = false, unique = true, length = 100, updatable = false)
    private String holdReference;

    @Column(name = "order_reference", nullable = false, unique = true, length = 100, updatable = false)
    private String orderReference;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "wallet_id", nullable = false, updatable = false)
    private PaymentWallet wallet;

    @Column(name = "owner_id", nullable = false, length = 100, updatable = false)
    private String ownerId;

    @Column(nullable = false, precision = 19, scale = 2, updatable = false)
    private BigDecimal amount;

    @Column(nullable = false, length = 3, updatable = false)
    private String currency;

    /**
     * Phần đã chuyển đi từ khoản giữ qua {@link #settle} — lệnh mua trên sổ lệnh Notes khớp từng
     * phần, mỗi lần khớp lấy một phần tiền giữ trả cho người bán. Phần còn lại vẫn giữ cho tới khi
     * {@link #release} nhả về ví. Khoản giữ của luồng gọi vốn sơ cấp luôn bằng 0.
     */
    @Column(name = "settled_amount", nullable = false, precision = 19, scale = 2)
    private BigDecimal settledAmount;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private PaymentHoldStatus status;

    @Column(name = "capture_reference", length = 100)
    private String captureReference;

    @Column(name = "held_at", nullable = false, updatable = false)
    private Instant heldAt;

    @Column(name = "released_at")
    private Instant releasedAt;

    @Column(name = "captured_at")
    private Instant capturedAt;

    @Version
    @Column(nullable = false)
    private Long version;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    public static PaymentHold create(
            UUID holdId,
            String holdReference,
            String orderReference,
            PaymentWallet wallet,
            BigDecimal amount,
            Instant now
    ) {
        PaymentHold hold = new PaymentHold();
        hold.holdId = Objects.requireNonNull(holdId, "holdId");
        hold.holdReference = requireText(holdReference, "holdReference");
        hold.orderReference = requireText(orderReference, "orderReference");
        hold.wallet = Objects.requireNonNull(wallet, "wallet");
        hold.ownerId = wallet.getOwnerId();
        hold.amount = Objects.requireNonNull(amount, "amount").setScale(2);
        if (hold.amount.signum() <= 0) {
            throw new IllegalArgumentException("amount phải dương");
        }
        hold.currency = wallet.getCurrency();
        hold.settledAmount = BigDecimal.ZERO.setScale(2);
        hold.status = PaymentHoldStatus.HELD;
        hold.heldAt = Objects.requireNonNull(now, "now");
        hold.createdAt = now;
        hold.updatedAt = now;
        return hold;
    }

    public boolean reserveCapture(String reference, Instant now) {
        String normalized = requireText(reference, "captureReference");
        if (status == PaymentHoldStatus.CAPTURE_PENDING && Objects.equals(captureReference, normalized)) {
            return false;
        }
        if (status == PaymentHoldStatus.CAPTURED && Objects.equals(captureReference, normalized)) {
            return false;
        }
        if (status != PaymentHoldStatus.HELD) {
            throw new IllegalStateException("Khoản tiền không còn sẵn sàng để capture");
        }
        // Capture giải ngân lấy trọn số giữ ban đầu; khoản đã chuyển đi một phần cho người bán Note
        // thì không còn đủ, và không bao giờ thuộc allocation giải ngân.
        if (settledAmount.signum() > 0) {
            throw new IllegalStateException("Khoản tiền đã được thanh toán một phần, không capture toàn bộ được");
        }
        captureReference = normalized;
        status = PaymentHoldStatus.CAPTURE_PENDING;
        updatedAt = Objects.requireNonNull(now, "now");
        return true;
    }

    public void cancelCaptureReservation(String reference, Instant now) {
        if (status == PaymentHoldStatus.CAPTURE_PENDING && Objects.equals(captureReference, reference)) {
            status = PaymentHoldStatus.HELD;
            captureReference = null;
            updatedAt = Objects.requireNonNull(now, "now");
        }
    }

    public void capture(String reference, Instant now) {
        if (status == PaymentHoldStatus.CAPTURED && Objects.equals(captureReference, reference)) {
            return;
        }
        if (status != PaymentHoldStatus.CAPTURE_PENDING || !Objects.equals(captureReference, reference)) {
            throw new IllegalStateException("Khoản tiền chưa được reserve cho lần capture này");
        }
        status = PaymentHoldStatus.CAPTURED;
        capturedAt = Objects.requireNonNull(now, "now");
        updatedAt = now;
    }

    /** Phần còn đang giữ: số giữ ban đầu trừ phần đã thanh toán. */
    public BigDecimal remainingAmount() {
        return amount.subtract(settledAmount);
    }

    /**
     * Ghi nhận một lần thanh toán từ khoản giữ. Chỉ khoản đang HELD mới thanh toán được, và tổng đã
     * thanh toán không vượt số giữ — bất biến tiền của khoản giữ.
     */
    public void settle(BigDecimal value, Instant now) {
        BigDecimal normalized = Objects.requireNonNull(value, "value").setScale(2);
        if (status != PaymentHoldStatus.HELD) {
            throw new IllegalStateException("Khoản tiền không còn ở trạng thái giữ");
        }
        if (normalized.signum() <= 0 || normalized.compareTo(remainingAmount()) > 0) {
            throw new IllegalArgumentException("Số tiền thanh toán vượt phần còn đang giữ");
        }
        settledAmount = settledAmount.add(normalized);
        updatedAt = Objects.requireNonNull(now, "now");
    }

    public boolean release(String actorId, Instant now) {
        requireOwner(actorId);
        if (status == PaymentHoldStatus.RELEASED) {
            return false;
        }
        if (status != PaymentHoldStatus.HELD) {
            throw new IllegalStateException("Khoản tiền đang giải ngân hoặc đã được capture");
        }
        status = PaymentHoldStatus.RELEASED;
        releasedAt = Objects.requireNonNull(now, "now");
        updatedAt = now;
        return true;
    }

    public void requireOwner(String actorId) {
        if (!ownerId.equals(actorId)) {
            throw new IllegalArgumentException("Ví không thuộc người dùng hiện tại");
        }
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " không được để trống");
        }
        String normalized = value.trim();
        if (normalized.length() > 100) {
            throw new IllegalArgumentException(field + " vượt quá 100 ký tự");
        }
        return normalized;
    }
}
