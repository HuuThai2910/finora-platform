package com.finora.payment.domain.ledger;

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
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;
import java.util.regex.Pattern;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "payment_ledger_entries")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class LedgerEntry {

    private static final Pattern ACCOUNT_CODE = Pattern.compile("^[A-Z0-9:_-]+$");

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "transaction_id", nullable = false, updatable = false)
    private LedgerTransaction transaction;

    @Column(name = "entry_sequence", nullable = false, updatable = false)
    private Integer entrySequence;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "wallet_id", updatable = false)
    private PaymentWallet wallet;

    @Column(name = "account_code", nullable = false, length = 150, updatable = false)
    private String accountCode;

    @Enumerated(EnumType.STRING)
    @Column(name = "balance_bucket", nullable = false, length = 20, updatable = false)
    private LedgerBalanceBucket balanceBucket;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10, updatable = false)
    private LedgerDirection direction;

    @Column(nullable = false, precision = 19, scale = 2, updatable = false)
    private BigDecimal amount;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    public static LedgerEntry create(
            LedgerTransaction transaction,
            int sequence,
            PaymentWallet wallet,
            String accountCode,
            LedgerBalanceBucket bucket,
            LedgerDirection direction,
            BigDecimal amount,
            Instant now
    ) {
        if (sequence < 1) {
            throw new IllegalArgumentException("entrySequence phải dương");
        }
        LedgerBalanceBucket requiredBucket = Objects.requireNonNull(bucket, "bucket");
        if ((requiredBucket == LedgerBalanceBucket.CLEARING) != (wallet == null)) {
            throw new IllegalArgumentException("CLEARING không gắn wallet; AVAILABLE/HELD bắt buộc có wallet");
        }
        String normalizedAccount = requireAccountCode(accountCode);
        BigDecimal normalizedAmount = money(amount);
        if (normalizedAmount.signum() <= 0) {
            throw new IllegalArgumentException("amount phải dương");
        }

        LedgerEntry entry = new LedgerEntry();
        entry.transaction = Objects.requireNonNull(transaction, "transaction");
        entry.entrySequence = sequence;
        entry.wallet = wallet;
        entry.accountCode = normalizedAccount;
        entry.balanceBucket = requiredBucket;
        entry.direction = Objects.requireNonNull(direction, "direction");
        entry.amount = normalizedAmount;
        entry.createdAt = Objects.requireNonNull(now, "now");
        return entry;
    }

    private static String requireAccountCode(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("accountCode không được để trống");
        }
        String normalized = value.trim();
        if (normalized.length() > 150 || !ACCOUNT_CODE.matcher(normalized).matches()) {
            throw new IllegalArgumentException("accountCode không đúng định dạng");
        }
        return normalized;
    }

    private static BigDecimal money(BigDecimal value) {
        Objects.requireNonNull(value, "amount");
        if (value.scale() > 2) {
            throw new IllegalArgumentException("amount không được quá 2 chữ số thập phân");
        }
        return value.setScale(2);
    }
}
