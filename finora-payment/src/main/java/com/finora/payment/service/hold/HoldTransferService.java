package com.finora.payment.service.hold;

import com.finora.common.exception.BusinessException;
import com.finora.common.security.SecurityUtils;
import com.finora.payment.domain.hold.PaymentHold;
import com.finora.payment.domain.ledger.LedgerBalanceBucket;
import com.finora.payment.domain.ledger.LedgerDirection;
import com.finora.payment.domain.ledger.LedgerTransactionType;
import com.finora.payment.domain.wallet.PaymentWallet;
import com.finora.payment.domain.wallet.WalletOwnerType;
import com.finora.payment.dto.request.CreateHoldRequest;
import com.finora.payment.dto.request.TransferRequest;
import com.finora.payment.dto.response.HoldResponse;
import com.finora.payment.dto.response.TransferResponse;
import com.finora.payment.repository.hold.PaymentHoldRepository;
import com.finora.payment.repository.wallet.PaymentWalletRepository;
import com.finora.payment.service.ledger.LedgerPostingCommand;
import com.finora.payment.service.ledger.LedgerPostingEntryCommand;
import com.finora.payment.service.ledger.LedgerPostingResult;
import com.finora.payment.service.ledger.LedgerPostingService;
import com.finora.payment.service.wallet.WalletAccountService;
import com.finora.payment.service.wallet.WalletView;
import java.math.BigDecimal;
import java.time.Clock;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class HoldTransferService {
    private final PaymentHoldRepository holdRepository;
    private final PaymentWalletRepository walletRepository;
    private final WalletAccountService walletAccountService;
    private final LedgerPostingService ledgerPostingService;
    private final Clock clock;

    @Transactional
    public HoldResponse hold(CreateHoldRequest request) {
        String actorId = SecurityUtils.getCurrentUserId();
        requireInvestor();
        requireActor(actorId, request.investorId());
        PaymentHold existing = holdRepository.findByOrderReference(request.orderReference()).orElse(null);
        if (existing != null) {
            if (!existing.getOwnerId().equals(actorId) || existing.getAmount().compareTo(request.amount()) != 0) {
                throw new BusinessException(HttpStatus.CONFLICT, "PAYMENT_HOLD_IDEMPOTENCY_CONFLICT",
                        "Mã lệnh đã được dùng với nội dung giữ tiền khác");
            }
            return HoldResponse.from(existing);
        }

        WalletView wallet = walletAccountService.open(WalletOwnerType.INVESTOR, actorId, "VND");
        PaymentWallet entity = walletRepository.findByWalletId(wallet.walletId()).orElseThrow();
        String holdReference = "HOLD-" + UUID.randomUUID();
        ledgerPostingService.post(new LedgerPostingCommand(
                "HOLD:" + request.orderReference(), LedgerTransactionType.HOLD,
                "INVESTMENT_ORDER", request.orderReference(), "VND", List.of(
                new LedgerPostingEntryCommand(entity.getWalletId(), null, LedgerBalanceBucket.AVAILABLE,
                        LedgerDirection.DEBIT, request.amount()),
                new LedgerPostingEntryCommand(entity.getWalletId(), null, LedgerBalanceBucket.HELD,
                        LedgerDirection.CREDIT, request.amount()))));
        PaymentHold hold = holdRepository.save(PaymentHold.create(
                UUID.randomUUID(), holdReference, request.orderReference(), entity, request.amount(), clock.instant()));
        return HoldResponse.from(hold);
    }

    @Transactional
    public HoldResponse release(String holdReference, String orderReference) {
        String actorId = SecurityUtils.getCurrentUserId();
        requireInvestor();
        PaymentHold hold = holdRepository.findByHoldReferenceForUpdate(holdReference)
                .orElseThrow(() -> new BusinessException(HttpStatus.NOT_FOUND, "PAYMENT_HOLD_NOT_FOUND", "Không tìm thấy khoản tiền giữ"));
        if (!hold.getOrderReference().equals(orderReference)) {
            throw new BusinessException(HttpStatus.CONFLICT, "PAYMENT_HOLD_ORDER_MISMATCH", "Mã lệnh không khớp khoản tiền giữ");
        }
        if (!hold.release(actorId, clock.instant())) return HoldResponse.from(hold);
        ledgerPostingService.post(new LedgerPostingCommand(
                "RELEASE:" + holdReference, LedgerTransactionType.RELEASE,
                "PAYMENT_HOLD", holdReference, hold.getCurrency(), List.of(
                new LedgerPostingEntryCommand(hold.getWallet().getWalletId(), null, LedgerBalanceBucket.HELD,
                        LedgerDirection.DEBIT, hold.getAmount()),
                new LedgerPostingEntryCommand(hold.getWallet().getWalletId(), null, LedgerBalanceBucket.AVAILABLE,
                        LedgerDirection.CREDIT, hold.getAmount()))));
        return HoldResponse.from(hold);
    }

    @Transactional
    public TransferResponse transfer(TransferRequest request) {
        String actorId = SecurityUtils.getCurrentUserId();
        requireInvestor();
        requireActor(actorId, request.buyerId());
        if (request.buyerId().equals(request.sellerId())) {
            throw new BusinessException(HttpStatus.UNPROCESSABLE_ENTITY, "PAYMENT_SELF_TRANSFER", "Không thể tự mua Note của chính mình");
        }
        if (request.platformFee().compareTo(request.price()) > 0) {
            throw new BusinessException(HttpStatus.UNPROCESSABLE_ENTITY, "PAYMENT_INVALID_FEE", "Phí không được lớn hơn giá chuyển nhượng");
        }
        WalletView buyer = walletAccountService.open(WalletOwnerType.INVESTOR, request.buyerId(), "VND");
        WalletView seller = walletAccountService.open(WalletOwnerType.INVESTOR, request.sellerId(), "VND");
        BigDecimal proceeds = request.price().subtract(request.platformFee()).setScale(2);
        List<LedgerPostingEntryCommand> entries = new java.util.ArrayList<>();
        entries.add(new LedgerPostingEntryCommand(buyer.walletId(), null, LedgerBalanceBucket.AVAILABLE,
                LedgerDirection.DEBIT, request.price()));
        if (proceeds.signum() > 0) {
            entries.add(new LedgerPostingEntryCommand(seller.walletId(), null, LedgerBalanceBucket.AVAILABLE,
                    LedgerDirection.CREDIT, proceeds));
        }
        if (request.platformFee().signum() > 0) {
            entries.add(new LedgerPostingEntryCommand(null, "PLATFORM_FEE", LedgerBalanceBucket.CLEARING,
                    LedgerDirection.CREDIT, request.platformFee()));
        }
        LedgerPostingResult result = ledgerPostingService.post(new LedgerPostingCommand(
                "TRANSFER:" + request.transferReference(), LedgerTransactionType.DISTRIBUTION,
                "NOTE_TRANSFER", request.transferReference(), "VND", entries));
        return new TransferResponse(result.transactionId().toString(), result.replayed());
    }

    private static void requireActor(String actorId, String expected) {
        if (!actorId.equals(expected)) {
            throw new BusinessException(HttpStatus.FORBIDDEN, "PAYMENT_OWNER_MISMATCH", "Không được thao tác ví của người dùng khác");
        }
    }

    private static void requireInvestor() {
        if (!SecurityUtils.hasRole("ROLE_INVESTOR")) {
            throw new BusinessException(HttpStatus.FORBIDDEN, "INVESTOR_ROLE_REQUIRED",
                    "Chỉ nhà đầu tư được thao tác giữ hoặc chuyển tiền đầu tư");
        }
    }
}
