package com.finora.payment.service.withdrawal;

import com.finora.payment.domain.ledger.LedgerBalanceBucket;
import com.finora.payment.domain.ledger.LedgerDirection;
import com.finora.payment.domain.ledger.LedgerTransactionType;
import com.finora.payment.service.ledger.LedgerPostingCommand;
import com.finora.payment.service.ledger.LedgerPostingEntryCommand;
import com.finora.payment.service.ledger.LedgerPostingService;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Bút toán của lệnh rút (D41). Dự trữ: ghi nợ ví {@code AVAILABLE} tổng tiền, ghi có clearing của provider payout (và
 * clearing phí nếu có). Hoàn: bút toán ngược đúng các dòng đó. Khóa idempotency theo mã lệnh nên gọi lại không ghi hai lần.
 * Không dùng {@code HELD}: bucket này được đối chiếu với khoản giữ góp vốn của Investment.
 */
@Component
@RequiredArgsConstructor
public class WithdrawalLedger {

    static final String PAYOUT_CLEARING = "PAYOUT_PROVIDER_CLEARING";
    static final String FEE_CLEARING = "PLATFORM_WITHDRAWAL_FEE";
    private static final String REFERENCE_TYPE = "WITHDRAWAL";

    private final LedgerPostingService ledgerPostingService;

    public UUID reserve(UUID withdrawalId, UUID walletId, BigDecimal amount, BigDecimal fee, String currency) {
        List<LedgerPostingEntryCommand> entries = new ArrayList<>();
        entries.add(new LedgerPostingEntryCommand(walletId, null, LedgerBalanceBucket.AVAILABLE, LedgerDirection.DEBIT,
                amount.add(fee)));
        entries.add(new LedgerPostingEntryCommand(null, PAYOUT_CLEARING, LedgerBalanceBucket.CLEARING,
                LedgerDirection.CREDIT, amount));
        if (fee.signum() > 0) {
            entries.add(new LedgerPostingEntryCommand(null, FEE_CLEARING, LedgerBalanceBucket.CLEARING,
                    LedgerDirection.CREDIT, fee));
        }
        return ledgerPostingService.post(new LedgerPostingCommand("WITHDRAWAL:" + withdrawalId + ":RESERVE",
                LedgerTransactionType.WITHDRAWAL, REFERENCE_TYPE, withdrawalId.toString(), currency, entries))
                .transactionId();
    }

    public UUID refund(UUID withdrawalId, UUID walletId, BigDecimal amount, BigDecimal fee, String currency) {
        List<LedgerPostingEntryCommand> entries = new ArrayList<>();
        entries.add(new LedgerPostingEntryCommand(null, PAYOUT_CLEARING, LedgerBalanceBucket.CLEARING,
                LedgerDirection.DEBIT, amount));
        if (fee.signum() > 0) {
            entries.add(new LedgerPostingEntryCommand(null, FEE_CLEARING, LedgerBalanceBucket.CLEARING,
                    LedgerDirection.DEBIT, fee));
        }
        entries.add(new LedgerPostingEntryCommand(walletId, null, LedgerBalanceBucket.AVAILABLE, LedgerDirection.CREDIT,
                amount.add(fee)));
        return ledgerPostingService.post(new LedgerPostingCommand("WITHDRAWAL:" + withdrawalId + ":REFUND",
                LedgerTransactionType.WITHDRAWAL, REFERENCE_TYPE, withdrawalId.toString(), currency, entries))
                .transactionId();
    }
}
