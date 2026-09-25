package com.finora.payment.domain.wallet;

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
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "payment_wallets")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class PaymentWallet {

    private static final Pattern CURRENCY = Pattern.compile("^[A-Z]{3}$");

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "wallet_id", nullable = false, unique = true, updatable = false)
    private UUID walletId;

    @Enumerated(EnumType.STRING)
    @Column(name = "owner_type", nullable = false, length = 30, updatable = false)
    private WalletOwnerType ownerType;

    @Column(name = "owner_id", nullable = false, length = 100, updatable = false)
    private String ownerId;

    @Column(nullable = false, length = 3, updatable = false)
    private String currency;

    @Column(name = "available_balance", nullable = false, precision = 19, scale = 2)
    private BigDecimal availableBalance;

    @Column(name = "held_balance", nullable = false, precision = 19, scale = 2)
    private BigDecimal heldBalance;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private WalletStatus status;

    @Version
    @Column(nullable = false)
    private Long version;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    public static PaymentWallet open(
            UUID walletId,
            WalletOwnerType ownerType,
            String ownerId,
            String currency,
            Instant now
    ) {
        PaymentWallet wallet = new PaymentWallet();
        wallet.walletId = Objects.requireNonNull(walletId, "walletId");
        wallet.ownerType = Objects.requireNonNull(ownerType, "ownerType");
        wallet.ownerId = requireText(ownerId, "ownerId", 100);
        wallet.currency = normalizeCurrency(currency);
        wallet.availableBalance = BigDecimal.ZERO.setScale(2);
        wallet.heldBalance = BigDecimal.ZERO.setScale(2);
        wallet.status = WalletStatus.ACTIVE;
        wallet.createdAt = Objects.requireNonNull(now, "now");
        wallet.updatedAt = now;
        return wallet;
    }

    public void apply(BigDecimal availableDelta, BigDecimal heldDelta, Instant now) {
        if (status != WalletStatus.ACTIVE) {
            throw new IllegalStateException("Wallet không ở trạng thái ACTIVE");
        }
        BigDecimal nextAvailable = availableBalance.add(money(availableDelta, "availableDelta"));
        BigDecimal nextHeld = heldBalance.add(money(heldDelta, "heldDelta"));
        if (nextAvailable.signum() < 0 || nextHeld.signum() < 0) {
            throw new IllegalArgumentException("Số dư available/held không được âm");
        }
        availableBalance = nextAvailable;
        heldBalance = nextHeld;
        updatedAt = Objects.requireNonNull(now, "now");
    }

    public static String normalizeCurrency(String currency) {
        String normalized = requireText(currency, "currency", 3).toUpperCase(Locale.ROOT);
        if (!CURRENCY.matcher(normalized).matches()) {
            throw new IllegalArgumentException("currency phải là mã ISO gồm 3 chữ cái");
        }
        return normalized;
    }

    private static BigDecimal money(BigDecimal value, String field) {
        Objects.requireNonNull(value, field);
        if (value.scale() > 2) {
            throw new IllegalArgumentException(field + " không được quá 2 chữ số thập phân");
        }
        return value.setScale(2);
    }

    private static String requireText(String value, String field, int maxLength) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " không được để trống");
        }
        String normalized = value.trim();
        if (normalized.length() > maxLength) {
            throw new IllegalArgumentException(field + " vượt quá " + maxLength + " ký tự");
        }
        return normalized;
    }
}
