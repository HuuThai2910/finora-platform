package com.finora.payment.service.ledger;

import com.finora.payment.domain.ledger.LedgerBalanceBucket;
import com.finora.payment.domain.ledger.LedgerDirection;
import java.math.BigDecimal;
import java.util.Objects;
import java.util.UUID;
import java.util.Locale;
import java.util.regex.Pattern;

public record LedgerPostingEntryCommand(
        UUID walletId,
        String clearingAccountCode,
        LedgerBalanceBucket balanceBucket,
        LedgerDirection direction,
        BigDecimal amount
) {
    private static final Pattern ACCOUNT_CODE = Pattern.compile("^[A-Z0-9:_-]+$");

    public LedgerPostingEntryCommand {
        balanceBucket = Objects.requireNonNull(balanceBucket, "balanceBucket");
        direction = Objects.requireNonNull(direction, "direction");
        amount = normalizeAmount(amount);
        if (balanceBucket == LedgerBalanceBucket.CLEARING) {
            if (walletId != null) {
                throw new IllegalArgumentException("Entry CLEARING không được gắn wallet");
            }
            clearingAccountCode = normalizeAccount(clearingAccountCode);
        } else {
            if (walletId == null) {
                throw new IllegalArgumentException("Entry AVAILABLE/HELD phải gắn wallet");
            }
            if (clearingAccountCode != null && !clearingAccountCode.isBlank()) {
                throw new IllegalArgumentException("Wallet entry không nhận clearingAccountCode");
            }
            clearingAccountCode = null;
        }
    }

    public String accountCode() {
        return balanceBucket == LedgerBalanceBucket.CLEARING
                ? clearingAccountCode
                : "WALLET:" + walletId.toString().toUpperCase(Locale.ROOT) + ":" + balanceBucket.name();
    }

    private static BigDecimal normalizeAmount(BigDecimal value) {
        Objects.requireNonNull(value, "amount");
        if (value.scale() > 2 || value.signum() <= 0) {
            throw new IllegalArgumentException("amount phải dương và không quá 2 chữ số thập phân");
        }
        return value.setScale(2);
    }

    private static String normalizeAccount(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("clearingAccountCode không được để trống");
        }
        String normalized = value.trim();
        if (normalized.length() > 150 || !ACCOUNT_CODE.matcher(normalized).matches()) {
            throw new IllegalArgumentException("clearingAccountCode không đúng định dạng");
        }
        return normalized;
    }
}
