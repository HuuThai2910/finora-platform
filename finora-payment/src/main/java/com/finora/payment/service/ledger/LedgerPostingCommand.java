package com.finora.payment.service.ledger;

import com.finora.payment.domain.ledger.LedgerTransactionType;
import com.finora.payment.domain.wallet.PaymentWallet;
import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;

public record LedgerPostingCommand(
        String idempotencyKey,
        LedgerTransactionType transactionType,
        String referenceType,
        String referenceId,
        String currency,
        List<LedgerPostingEntryCommand> entries
) {
    private static final Pattern REFERENCE_TYPE = Pattern.compile("^[A-Z][A-Z0-9_]{0,49}$");

    public LedgerPostingCommand {
        idempotencyKey = requireText(idempotencyKey, "idempotencyKey", 100);
        transactionType = Objects.requireNonNull(transactionType, "transactionType");
        referenceType = requireText(referenceType, "referenceType", 50);
        if (!REFERENCE_TYPE.matcher(referenceType).matches()) {
            throw new IllegalArgumentException("referenceType không đúng định dạng");
        }
        referenceId = requireText(referenceId, "referenceId", 100);
        currency = PaymentWallet.normalizeCurrency(currency);
        entries = entries == null ? List.of() : List.copyOf(entries);
        if (entries.size() < 2 || entries.size() > 100) {
            throw new IllegalArgumentException("Một transaction cần từ 2 đến 100 entries");
        }
        if (entries.stream().noneMatch(entry -> entry.walletId() != null)) {
            throw new IllegalArgumentException("Transaction phải ảnh hưởng ít nhất một wallet");
        }
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
