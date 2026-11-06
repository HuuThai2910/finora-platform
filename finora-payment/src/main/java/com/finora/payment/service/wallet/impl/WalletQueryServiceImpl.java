package com.finora.payment.service.wallet.impl;

import com.finora.common.security.SecurityUtils;
import com.finora.payment.domain.ledger.entity.LedgerEntry;
import com.finora.payment.domain.ledger.enums.LedgerDirection;
import com.finora.payment.domain.wallet.entity.PaymentWallet;
import com.finora.payment.dto.response.WalletBalanceResponse;
import com.finora.payment.dto.response.WalletTransactionResponse;
import com.finora.payment.repository.ledger.LedgerEntryRepository;
import com.finora.payment.repository.wallet.PaymentWalletRepository;
import com.finora.payment.service.wallet.WalletAccountService;
import com.finora.payment.service.wallet.WalletOwnerResolver;
import com.finora.payment.service.wallet.WalletQueryService;
import com.finora.payment.service.wallet.WalletView;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Xem số dư và sao kê ví VND của người dùng đang đăng nhập.
 * <p>
 * Loại chủ ví lấy theo vai trò hiện tại; mô tả từng dòng sao kê suy từ loại ledger transaction.
 */
@Service
@RequiredArgsConstructor
public class WalletQueryServiceImpl implements WalletQueryService {
    private final WalletAccountService walletAccountService;
    private final WalletOwnerResolver walletOwnerResolver;
    private final PaymentWalletRepository paymentWalletRepository;
    private final LedgerEntryRepository ledgerEntryRepository;

    @Override
    @Transactional
    public WalletBalanceResponse currentBalance() {
        String ownerId = SecurityUtils.getCurrentUserId();
        WalletView view = walletAccountService.open(walletOwnerResolver.currentOwnerType(), ownerId, "VND");
        return WalletBalanceResponse.from(view);
    }

    @Override
    @Transactional(readOnly = true)
    public List<WalletTransactionResponse> currentStatement() {
        String ownerId = SecurityUtils.getCurrentUserId();
        PaymentWallet wallet = paymentWalletRepository.findByOwnerTypeAndOwnerIdAndCurrency(
                        walletOwnerResolver.currentOwnerType(), ownerId, "VND")
                .orElse(null);
        if (wallet == null) return List.of();
        return ledgerEntryRepository.findWalletEntries(wallet.getWalletId(), PageRequest.of(0, 100)).stream()
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
