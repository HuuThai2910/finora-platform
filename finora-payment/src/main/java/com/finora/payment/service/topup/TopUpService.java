package com.finora.payment.service.topup;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.finora.common.exception.BusinessException;
import com.finora.common.security.SecurityUtils;
import com.finora.payment.config.TopUpProperties;
import com.finora.payment.config.ZaloPayProperties;
import com.finora.payment.domain.ledger.LedgerBalanceBucket;
import com.finora.payment.domain.ledger.LedgerDirection;
import com.finora.payment.domain.ledger.LedgerTransactionType;
import com.finora.payment.domain.topup.PaymentTopUp;
import com.finora.payment.domain.wallet.PaymentWallet;
import com.finora.payment.dto.response.TopUpResponse;
import com.finora.payment.integration.topup.TopUpProvider;
import com.finora.payment.integration.topup.ZaloPayMac;
import com.finora.payment.repository.topup.PaymentTopUpRepository;
import com.finora.payment.repository.wallet.PaymentWalletRepository;
import com.finora.payment.service.ledger.LedgerPostingCommand;
import com.finora.payment.service.ledger.LedgerPostingEntryCommand;
import com.finora.payment.service.ledger.LedgerPostingService;
import com.finora.payment.service.wallet.WalletAccountService;
import com.finora.payment.service.wallet.WalletOwnerResolver;
import com.finora.payment.service.wallet.WalletView;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

@Service
@RequiredArgsConstructor
public class TopUpService {
    private final PaymentTopUpRepository topUpRepository;
    private final PaymentWalletRepository walletRepository;
    private final WalletAccountService walletAccountService;
    private final WalletOwnerResolver ownerResolver;
    private final LedgerPostingService ledgerPostingService;
    private final TopUpProvider provider;
    private final TopUpProperties properties;
    private final ZaloPayProperties zaloPayProperties;
    private final ObjectMapper objectMapper;
    private final TransactionTemplate transactionTemplate;
    private final Clock clock;

    public TopUpResponse create(BigDecimal amount, String idempotencyKey) {
        String ownerId = SecurityUtils.getCurrentUserId();
        BigDecimal normalized = validateAmount(amount);
        String hash = sha256(ownerId + "|" + normalized.toPlainString() + "|VND");
        PaymentTopUp prepared = transactionTemplate.execute(status ->
                prepare(ownerId, normalized, requireText(idempotencyKey, "Idempotency-Key"), hash));
        if (prepared == null) throw new IllegalStateException("Không tạo được lệnh nạp tiền");
        if (!prepared.getStatus().name().equals("PROVIDER_PENDING")) return TopUpResponse.from(prepared);

        try {
            TopUpProvider.TopUpProviderResult result = provider.create(new TopUpProvider.TopUpProviderCommand(
                    prepared.getTopUpId(), prepared.getProviderOrderId(), ownerId, normalized, "VND"));
            return transactionTemplate.execute(status -> {
                PaymentTopUp locked = topUpRepository.findByTopUpIdForUpdate(prepared.getTopUpId()).orElseThrow();
                locked.awaitingPayment(result.checkoutUrl(), result.qrPayload(), result.expiresAt(), clock.instant());
                return TopUpResponse.from(locked);
            });
        } catch (RuntimeException exception) {
            transactionTemplate.executeWithoutResult(status -> {
                PaymentTopUp locked = topUpRepository.findByTopUpIdForUpdate(prepared.getTopUpId()).orElseThrow();
                locked.fail("TOPUP_PROVIDER_UNCERTAIN", exception.getMessage(), true, clock.instant());
            });
            throw new BusinessException(HttpStatus.SERVICE_UNAVAILABLE, "TOPUP_PROVIDER_UNAVAILABLE",
                    "Chưa xác định được kết quả tạo giao dịch nạp tiền; vui lòng kiểm tra lại");
        }
    }

    public TopUpResponse get(UUID topUpId) {
        String ownerId = SecurityUtils.getCurrentUserId();
        PaymentTopUp topUp = topUpRepository.findByTopUpId(topUpId)
                .orElseThrow(() -> new BusinessException(HttpStatus.NOT_FOUND, "TOPUP_NOT_FOUND", "Không tìm thấy lệnh nạp tiền"));
        requireOwner(topUp, ownerId);
        return TopUpResponse.from(topUp);
    }

    public TopUpResponse completeMock(UUID topUpId) {
        String ownerId = SecurityUtils.getCurrentUserId();
        return transactionTemplate.execute(status -> {
            PaymentTopUp topUp = topUpRepository.findByTopUpIdForUpdate(topUpId)
                    .orElseThrow(() -> new BusinessException(HttpStatus.NOT_FOUND, "TOPUP_NOT_FOUND", "Không tìm thấy lệnh nạp tiền"));
            requireOwner(topUp, ownerId);
            if (!"MOCK".equals(topUp.getProvider())) {
                throw new BusinessException(HttpStatus.CONFLICT, "TOPUP_NOT_MOCK", "Chỉ lệnh mock mới được xác nhận thủ công");
            }
            credit(topUp, "MOCK-" + topUp.getTopUpId());
            return TopUpResponse.from(topUp);
        });
    }

    public boolean processZaloPayCallback(String data, String mac) {
        if (zaloPayProperties.key2() == null || zaloPayProperties.key2().isBlank()) return false;
        String expected = ZaloPayMac.hmacSha256(data == null ? "" : data,
                zaloPayProperties.key2());
        if (!ZaloPayMac.matches(expected, mac)) return false;
        try {
            JsonNode payload = objectMapper.readTree(data);
            String orderId = payload.path("app_trans_id").asText();
            BigDecimal amount = payload.path("amount").decimalValue().setScale(2);
            String reference = payload.path("zp_trans_id").asText();
            transactionTemplate.executeWithoutResult(status -> {
                PaymentTopUp topUp = topUpRepository.findByProviderOrderForUpdate("ZALOPAY", orderId)
                        .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy app_trans_id"));
                if (topUp.getAmount().compareTo(amount) != 0) {
                    throw new IllegalArgumentException("Số tiền callback không khớp đơn nạp");
                }
                credit(topUp, reference);
            });
            return true;
        } catch (RuntimeException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new IllegalArgumentException("Callback ZaloPay không đúng JSON", exception);
        }
    }

    private PaymentTopUp prepare(String ownerId, BigDecimal amount, String key, String hash) {
        PaymentTopUp existing = topUpRepository.findByIdempotencyKey(key).orElse(null);
        if (existing != null) {
            requireOwner(existing, ownerId);
            if (!existing.getRequestHash().equals(hash)) {
                throw new BusinessException(HttpStatus.CONFLICT, "TOPUP_IDEMPOTENCY_CONFLICT",
                        "Idempotency-Key đã được dùng cho lệnh nạp khác");
            }
            return existing;
        }
        WalletView wallet = walletAccountService.open(ownerResolver.currentOwnerType(), ownerId, "VND");
        PaymentWallet entity = walletRepository.findByWalletId(wallet.walletId()).orElseThrow();
        UUID id = UUID.randomUUID();
        String prefix = DateTimeFormatter.ofPattern("yyMMdd").withZone(ZoneOffset.UTC).format(clock.instant());
        String providerOrderId = prefix + "_" + id.toString().replace("-", "").substring(0, 20);
        return topUpRepository.save(PaymentTopUp.prepare(
                id, entity, amount, provider.name(), providerOrderId, key, hash, clock.instant()));
    }

    private void credit(PaymentTopUp topUp, String providerReference) {
        if (!topUp.complete(providerReference, clock.instant())) return;
        ledgerPostingService.post(new LedgerPostingCommand(
                "TOPUP:" + topUp.getTopUpId(), LedgerTransactionType.DEPOSIT,
                "TOP_UP", topUp.getTopUpId().toString(), topUp.getCurrency(), List.of(
                new LedgerPostingEntryCommand(null, "PROVIDER_CLEARING", LedgerBalanceBucket.CLEARING,
                        LedgerDirection.DEBIT, topUp.getAmount()),
                new LedgerPostingEntryCommand(topUp.getWallet().getWalletId(), null, LedgerBalanceBucket.AVAILABLE,
                        LedgerDirection.CREDIT, topUp.getAmount()))));
    }

    private BigDecimal validateAmount(BigDecimal amount) {
        if (amount == null || amount.scale() > 0) throw new IllegalArgumentException("Số tiền nạp phải là số nguyên đồng");
        BigDecimal normalized = amount.setScale(2);
        if (normalized.compareTo(properties.minimumAmount()) < 0 || normalized.compareTo(properties.maximumAmount()) > 0) {
            throw new BusinessException(HttpStatus.UNPROCESSABLE_ENTITY, "TOPUP_AMOUNT_OUT_OF_RANGE", "Số tiền nạp ngoài giới hạn cho phép");
        }
        return normalized;
    }

    private static void requireOwner(PaymentTopUp topUp, String ownerId) {
        if (!topUp.getOwnerId().equals(ownerId)) throw new BusinessException(HttpStatus.FORBIDDEN, "TOPUP_ACCESS_DENIED", "Bạn không sở hữu lệnh nạp này");
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " không được để trống");
        return value.trim();
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new IllegalStateException("JVM không hỗ trợ SHA-256", exception);
        }
    }
}
