package com.finora.payment.service.withdrawal.impl;

import com.finora.common.dto.PageResponse;
import com.finora.common.exception.BusinessException;
import com.finora.common.security.SecurityUtils;
import com.finora.payment.config.PayoutProperties;
import com.finora.payment.config.WithdrawalProperties;
import com.finora.payment.domain.wallet.entity.PaymentWallet;
import com.finora.payment.domain.wallet.enums.WalletOwnerType;
import com.finora.payment.domain.withdrawal.entity.PaymentWithdrawal;
import com.finora.payment.dto.request.CreateWithdrawalRequest;
import com.finora.payment.dto.response.WithdrawalPolicyResponse;
import com.finora.payment.dto.response.WithdrawalResponse;
import com.finora.payment.integration.payout.client.PayoutProvider;
import com.finora.payment.repository.wallet.PaymentWalletRepository;
import com.finora.payment.repository.withdrawal.PaymentWithdrawalRepository;
import com.finora.payment.service.wallet.WalletAccountService;
import com.finora.payment.service.wallet.WalletOwnerResolver;
import com.finora.payment.service.wallet.WalletView;
import com.finora.payment.service.withdrawal.WithdrawalBank;
import com.finora.payment.service.withdrawal.WithdrawalLedger;
import com.finora.payment.service.withdrawal.WithdrawalService;
import com.finora.payment.util.AccountNumberCipher;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

/**
 * Nhận lệnh rút tiền của người dùng (R4, W3B). Trong một transaction: khóa dòng ví, kiểm policy (tối thiểu, hạn mức ngày
 * theo ngày nghiệp vụ Việt Nam, số dư khả dụng), trừ ví bằng bút toán dự trữ và lưu lệnh {@code REQUESTED}. Gọi provider
 * là việc của worker, ngoài transaction này.
 */
@Service
@RequiredArgsConstructor
public class WithdrawalServiceImpl implements WithdrawalService {

    private static final String CURRENCY = "VND";

    private final PaymentWithdrawalRepository paymentWithdrawalRepository;
    private final PaymentWalletRepository paymentWalletRepository;
    private final WalletAccountService walletAccountService;
    private final WalletOwnerResolver walletOwnerResolver;
    private final WithdrawalLedger withdrawalLedger;
    private final AccountNumberCipher accountNumberCipher;
    private final WithdrawalProperties withdrawalProperties;
    private final PayoutProperties payoutProperties;
    private final PayoutProvider payoutProvider;
    private final TransactionTemplate transactionTemplate;
    private final Clock clock;

    @Override
    public WithdrawalPolicyResponse policy() {
        String ownerId = SecurityUtils.getCurrentUserId();
        WalletView wallet = walletAccountService.open(walletOwnerResolver.currentOwnerType(), ownerId, CURRENCY);
        LocalDate today = LocalDate.now(clock);
        BigDecimal used = paymentWithdrawalRepository.sumNotFailedOn(wallet.walletId(), today).setScale(2);
        BigDecimal remaining = withdrawalProperties.dailyLimit().subtract(used).max(BigDecimal.ZERO);
        List<WithdrawalPolicyResponse.Bank> banks = Arrays.stream(WithdrawalBank.values())
                .map(bank -> new WithdrawalPolicyResponse.Bank(bank.name(), bank.displayName())).toList();
        return new WithdrawalPolicyResponse(withdrawalProperties.minimumAmount(), withdrawalProperties.dailyLimit(),
                withdrawalProperties.feeAmount(), today, used, remaining, wallet.availableBalance(),
                "mock".equals(payoutProperties.provider()), banks);
    }

    @Override
    public WithdrawalResponse request(CreateWithdrawalRequest request, String idempotencyKey) {
        String ownerId = SecurityUtils.getCurrentUserId();
        WalletOwnerType ownerType = walletOwnerResolver.currentOwnerType();
        String key = requireKey(idempotencyKey);
        BigDecimal amount = validateAmount(request.amount());
        WithdrawalBank bank = WithdrawalBank.find(request.bankCode()).orElseThrow(() -> new BusinessException(
                HttpStatus.UNPROCESSABLE_ENTITY, "WITHDRAWAL_BANK_UNSUPPORTED", "Ngân hàng nhận chưa được hỗ trợ"));
        String accountNumber = request.accountNumber().trim();
        String holder = normalizeHolder(request.accountHolderName());
        // Hash idempotency dùng dấu vân tay HMAC của số tài khoản, không lưu dạng dò ngược được (D44).
        String hash = sha256(String.join("|", ownerId, amount.toPlainString(), bank.name(),
                accountNumberCipher.fingerprint(accountNumber), holder));

        Optional<WithdrawalResponse> replay = replay(ownerId, key, hash);
        if (replay.isPresent()) return replay.get();

        WalletView wallet = walletAccountService.open(ownerType, ownerId, CURRENCY);
        try {
            return transactionTemplate.execute(status -> {
                // Khóa dòng ví trước khi cộng hạn mức: hai lệnh đồng thời không cùng thấy còn hạn mức (D41).
                PaymentWallet locked = paymentWalletRepository.findAllByWalletIdInForUpdate(List.of(wallet.walletId()))
                        .stream().findFirst().orElseThrow();
                Optional<WithdrawalResponse> concurrent = replay(ownerId, key, hash);
                if (concurrent.isPresent()) return concurrent.get();
                Instant now = clock.instant();
                LocalDate today = LocalDate.now(clock);
                BigDecimal used = paymentWithdrawalRepository.sumNotFailedOn(locked.getWalletId(), today);
                BigDecimal remaining = withdrawalProperties.dailyLimit().subtract(used);
                if (amount.compareTo(remaining) > 0) {
                    throw new BusinessException(HttpStatus.UNPROCESSABLE_ENTITY, "WITHDRAWAL_DAILY_LIMIT",
                            "Vượt hạn mức rút trong ngày; hôm nay còn được rút "
                                    + remaining.max(BigDecimal.ZERO).toBigInteger() + " đ");
                }
                BigDecimal total = amount.add(withdrawalProperties.feeAmount());
                if (locked.getAvailableBalance().compareTo(total) < 0) {
                    throw new BusinessException(HttpStatus.UNPROCESSABLE_ENTITY, "WITHDRAWAL_INSUFFICIENT_BALANCE",
                            "Số dư khả dụng không đủ để rút số tiền này");
                }
                UUID withdrawalId = UUID.randomUUID();
                UUID reserve = withdrawalLedger.reserve(withdrawalId, locked.getWalletId(), amount,
                        withdrawalProperties.feeAmount(), CURRENCY);
                PaymentWithdrawal created = paymentWithdrawalRepository.saveAndFlush(PaymentWithdrawal.request(
                        withdrawalId, locked.getWalletId(), ownerType, ownerId, amount,
                        withdrawalProperties.feeAmount(), CURRENCY, today,
                        new PaymentWithdrawal.Destination(bank.name(), accountNumberCipher.encrypt(accountNumber),
                                AccountNumberCipher.mask(accountNumber), holder),
                        key, hash, payoutProvider.name(), reserve, now));
                return WithdrawalResponse.from(created);
            });
        } catch (DataIntegrityViolationException exception) {
            // Hai request cùng Idempotency-Key chạy song song: bản thua đọc lại lệnh của bản thắng.
            return replay(ownerId, key, hash).orElseThrow(() -> exception);
        }
    }

    @Override
    public PageResponse<WithdrawalResponse> list(int page, int size) {
        String ownerId = SecurityUtils.getCurrentUserId();
        Page<WithdrawalResponse> result = paymentWithdrawalRepository
                .findByOwnerIdOrderByCreatedAtDescIdDesc(ownerId, PageRequest.of(page, size))
                .map(WithdrawalResponse::from);
        return PageResponse.<WithdrawalResponse>builder().content(result.getContent()).page(result.getNumber())
                .size(result.getSize()).totalElements(result.getTotalElements()).totalPages(result.getTotalPages())
                .last(result.isLast()).build();
    }

    @Override
    public WithdrawalResponse get(UUID withdrawalId) {
        String ownerId = SecurityUtils.getCurrentUserId();
        // Lệnh của người khác trả 404 như không tồn tại, không để lộ mã lệnh hợp lệ.
        return paymentWithdrawalRepository.findByWithdrawalId(withdrawalId)
                .filter(value -> value.getOwnerId().equals(ownerId))
                .map(WithdrawalResponse::from)
                .orElseThrow(() -> new BusinessException(HttpStatus.NOT_FOUND, "WITHDRAWAL_NOT_FOUND",
                        "Không tìm thấy lệnh rút tiền"));
    }

    private Optional<WithdrawalResponse> replay(String ownerId, String key, String hash) {
        return paymentWithdrawalRepository.findByOwnerIdAndIdempotencyKey(ownerId, key).map(existing -> {
            if (!existing.getRequestHash().equals(hash)) {
                throw new BusinessException(HttpStatus.CONFLICT, "WITHDRAWAL_IDEMPOTENCY_CONFLICT",
                        "Idempotency-Key đã được dùng cho một lệnh rút khác");
            }
            return WithdrawalResponse.from(existing);
        });
    }

    private BigDecimal validateAmount(BigDecimal amount) {
        if (amount == null || amount.stripTrailingZeros().scale() > 0) {
            throw new BusinessException(HttpStatus.UNPROCESSABLE_ENTITY, "WITHDRAWAL_AMOUNT_INVALID",
                    "Số tiền rút phải là số nguyên đồng");
        }
        BigDecimal normalized = amount.setScale(2);
        if (normalized.compareTo(withdrawalProperties.minimumAmount()) < 0) {
            throw new BusinessException(HttpStatus.UNPROCESSABLE_ENTITY, "WITHDRAWAL_BELOW_MINIMUM",
                    "Mỗi lần rút tối thiểu " + withdrawalProperties.minimumAmount().toBigInteger() + " đ");
        }
        return normalized;
    }

    private static String normalizeHolder(String value) {
        String collapsed = value.trim().replaceAll("\\s+", " ").toUpperCase(Locale.ROOT);
        if (collapsed.length() < 2 || !collapsed.matches("[\\p{L} ]+")) {
            throw new BusinessException(HttpStatus.UNPROCESSABLE_ENTITY, "WITHDRAWAL_HOLDER_INVALID",
                    "Tên chủ tài khoản chỉ gồm chữ cái và khoảng trắng");
        }
        return collapsed;
    }

    private static String requireKey(String value) {
        if (value == null || value.isBlank() || value.length() > 150) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "IDEMPOTENCY_KEY_REQUIRED",
                    "Lệnh rút cần Idempotency-Key (tối đa 150 ký tự)");
        }
        return value;
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }
}
