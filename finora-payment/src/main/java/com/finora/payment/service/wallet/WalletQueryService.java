package com.finora.payment.service.wallet;

import com.finora.common.exception.BusinessException;
import com.finora.common.security.SecurityUtils;
import com.finora.payment.domain.ledger.LedgerDirection;
import com.finora.payment.domain.ledger.LedgerEntry;
import com.finora.payment.domain.wallet.PaymentWallet;
import com.finora.payment.dto.response.WalletBalanceResponse;
import com.finora.payment.dto.response.WalletTransactionResponse;
import com.finora.payment.repository.ledger.LedgerEntryRepository;
import com.finora.payment.repository.wallet.PaymentWalletRepository;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class WalletQueryService {
    private final WalletAccountService accountService;
    private final WalletOwnerResolver ownerResolver;
    private final PaymentWalletRepository walletRepository;
    private final LedgerEntryRepository entryRepository;

    @Transactional
    public WalletBalanceResponse currentBalance() {
        String ownerId = SecurityUtils.getCurrentUserId();
        WalletView view = accountService.open(ownerResolver.currentOwnerType(), ownerId, "VND");
        return WalletBalanceResponse.from(view);
    }

    @Transactional(readOnly = true)
    public List<WalletTransactionResponse> currentStatement() {
        String ownerId = SecurityUtils.getCurrentUserId();
        PaymentWallet wallet = walletRepository.findByOwnerTypeAndOwnerIdAndCurrency(
                        ownerResolver.currentOwnerType(), ownerId, "VND")
                .orElse(null);
        if (wallet == null) return List.of();
        return entryRepository.findWalletEntries(wallet.getWalletId(), PageRequest.of(0, 100)).stream()
                .map(this::toResponse)
                .toList();
    }

    private WalletTransactionResponse toResponse(LedgerEntry entry) {
        var transaction = entry.getTransaction();
        boolean credit = entry.getDirection() == LedgerDirection.CREDIT;
        String description = switch (transaction.getTransactionType()) {
            case DEPOSIT -> "Nạp tiền vào ví";
            case HOLD -> "Giữ tiền cho lệnh đầu tư";
            case RELEASE -> "Hoàn tiền lệnh đầu tư";
            case CAPTURE, DISBURSEMENT -> "Giải ngân khoản vay";
            case DISTRIBUTION -> "Nhận tiền thanh toán";
            case WITHDRAWAL -> "Rút tiền";
            case REPAYMENT -> "Thanh toán khoản vay";
            case ADJUSTMENT -> "Điều chỉnh số dư";
        };
        return new WalletTransactionResponse(
                transaction.getTransactionId(), transaction.getPostedAt(),
                transaction.getTransactionType().name(), description,
                entry.getAmount(), credit ? "CREDIT" : "DEBIT", transaction.getReferenceId());
    }
}
