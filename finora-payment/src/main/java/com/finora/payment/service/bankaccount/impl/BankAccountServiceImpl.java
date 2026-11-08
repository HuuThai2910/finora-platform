package com.finora.payment.service.bankaccount.impl;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.finora.common.exception.BusinessException;
import com.finora.common.security.SecurityUtils;
import com.finora.payment.config.BankLinkProperties;
import com.finora.payment.domain.bankaccount.entity.PaymentBankAccount;
import com.finora.payment.domain.bankaccount.entity.PaymentBankLinkRequest;
import com.finora.payment.domain.bankaccount.enums.BankLinkRequestStatus;
import com.finora.payment.domain.outbox.entity.PaymentOutboxEvent;
import com.finora.payment.dto.request.BankAccountInquiryRequest;
import com.finora.payment.dto.request.ConfirmBankLinkRequest;
import com.finora.payment.dto.request.StartBankLinkRequest;
import com.finora.payment.dto.response.BankAccountInquiryResponse;
import com.finora.payment.dto.response.BankAccountResponse;
import com.finora.payment.dto.response.BankLinkChallengeResponse;
import com.finora.payment.dto.response.BankResponse;
import com.finora.payment.integration.bank.client.BankAccountProvider;
import com.finora.payment.integration.user.client.UserIdentityClient;
import com.finora.payment.messaging.event.BankLinkOtpEventData;
import com.finora.payment.repository.PaymentOutboxEventRepository;
import com.finora.payment.repository.bankaccount.PaymentBankAccountRepository;
import com.finora.payment.repository.bankaccount.PaymentBankLinkRequestRepository;
import com.finora.payment.service.bank.BankDirectory;
import com.finora.payment.service.bank.BankInfo;
import com.finora.payment.service.bankaccount.BankAccountService;
import com.finora.payment.util.AccountNumberCipher;
import com.finora.payment.util.VietnameseNames;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Liên kết tài khoản ngân hàng (BL-001). Ba lớp kiểm chạy ở server, không tin app:
 * <ol>
 *   <li>người dùng đã eKYC ({@code VERIFIED}, đọc từ finora-user bằng token của chính họ);</li>
 *   <li>ngân hàng xác nhận tài khoản tồn tại, nhận được tiền và <b>đứng tên đúng họ tên eKYC</b>;</li>
 *   <li>người dùng nhập đúng OTP ngân hàng gửi, trong hạn và chưa sai quá số lần cho phép.</li>
 * </ol>
 * Số tài khoản lưu dạng mã hóa AES-GCM như lệnh rút (D44); so trùng bằng dấu HMAC, không giải mã.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BankAccountServiceImpl implements BankAccountService {

    static final String OTP_EVENT_TYPE = "BankLinkOtpRequested";
    private static final int OTP_DIGITS = 6;

    private final PaymentBankAccountRepository bankAccountRepository;
    private final PaymentBankLinkRequestRepository linkRequestRepository;
    private final PaymentOutboxEventRepository outboxEventRepository;
    private final BankDirectory bankDirectory;
    private final BankAccountProvider bankAccountProvider;
    private final UserIdentityClient userIdentityClient;
    private final AccountNumberCipher accountNumberCipher;
    private final BankLinkProperties properties;
    private final TransactionTemplate transactionTemplate;
    private final ObjectMapper objectMapper;
    private final Clock clock;
    private final SecureRandom random = new SecureRandom();

    @Override
    public List<BankResponse> banks() {
        return bankDirectory.all().stream().map(BankResponse::from).toList();
    }

    @Override
    public List<BankAccountResponse> list() {
        return bankAccountRepository.findActiveByOwner(SecurityUtils.getCurrentUserId()).stream()
                .map(this::toResponse).toList();
    }

    @Override
    public BankAccountInquiryResponse inquire(BankAccountInquiryRequest request) {
        BankInfo bank = requireBank(request.bankCode());
        UserIdentityClient.CurrentUser user = requireVerifiedUser();
        String accountNumber = request.accountNumber().trim();
        String holder = lookUpHolder(bank, accountNumber, user.fullName());
        return new BankAccountInquiryResponse(bank.code(), bank.shortName(), AccountNumberCipher.mask(accountNumber),
                holder, VietnameseNames.sameName(holder, user.fullName()));
    }

    @Override
    public BankLinkChallengeResponse startLink(StartBankLinkRequest request, String idempotencyKey) {
        String ownerId = SecurityUtils.getCurrentUserId();
        String key = requireKey(idempotencyKey);
        BankInfo bank = requireBank(request.bankCode());
        String accountNumber = request.accountNumber().trim();
        String fingerprint = accountNumberCipher.fingerprint(accountNumber);

        Optional<BankLinkChallengeResponse> replay = replay(ownerId, key, bank.code(), fingerprint);
        if (replay.isPresent()) return replay.get();

        UserIdentityClient.CurrentUser user = requireVerifiedUser();
        List<PaymentBankAccount> active = bankAccountRepository.findActiveByOwner(ownerId);
        requireRoomFor(active, bank.code(), fingerprint);
        // Tra cứu lại ở server dù app đã tra: tên chủ tài khoản luôn lấy từ ngân hàng, không lấy từ app.
        String holder = lookUpHolder(bank, accountNumber, user.fullName());
        if (!VietnameseNames.sameName(holder, user.fullName())) {
            throw new BusinessException(HttpStatus.UNPROCESSABLE_ENTITY, "BANK_ACCOUNT_NAME_MISMATCH",
                    "Tài khoản đứng tên " + holder + ", không trùng họ tên đã xác minh của bạn. Chỉ liên kết được tài "
                            + "khoản ngân hàng đứng tên chính bạn.");
        }

        Instant now = clock.instant();
        UUID linkRequestId = UUID.randomUUID();
        String otp = newOtp();
        PaymentBankLinkRequest.Account account = new PaymentBankLinkRequest.Account(bank.code(),
                accountNumberCipher.encrypt(accountNumber), fingerprint, AccountNumberCipher.mask(accountNumber),
                holder);
        try {
            PaymentBankLinkRequest saved = transactionTemplate.execute(status -> {
                PaymentBankLinkRequest created = linkRequestRepository.saveAndFlush(PaymentBankLinkRequest.start(
                        linkRequestId, ownerId, account, otpHash(linkRequestId, otp), now.plus(properties.otpTtl()),
                        key, now));
                sendOtp(created, bank, otp, now);
                return created;
            });
            log.info("Bắt đầu liên kết ngân hàng: linkRequestId={}, bank={}, provider={}", linkRequestId, bank.code(),
                    bankAccountProvider.name());
            return toChallenge(saved);
        } catch (DataIntegrityViolationException exception) {
            // Hai request cùng Idempotency-Key chạy song song: bản thua đọc lại yêu cầu của bản thắng.
            return replay(ownerId, key, bank.code(), fingerprint).orElseThrow(() -> exception);
        }
    }

    @Override
    public BankLinkChallengeResponse resendOtp(UUID linkRequestId) {
        String ownerId = SecurityUtils.getCurrentUserId();
        return transactionTemplate.execute(status -> {
            PaymentBankLinkRequest request = lockOwnRequest(linkRequestId, ownerId);
            requirePending(request);
            Instant now = clock.instant();
            if (request.getResendCount() >= properties.otpMaxResends()) {
                throw new BusinessException(HttpStatus.UNPROCESSABLE_ENTITY, "BANK_LINK_RESEND_LIMIT",
                        "Bạn đã xin gửi lại mã quá số lần cho phép. Hãy bắt đầu liên kết lại.");
            }
            Instant availableAt = request.getOtpSentAt().plus(properties.otpResendCooldown());
            if (now.isBefore(availableAt)) {
                throw new BusinessException(HttpStatus.TOO_MANY_REQUESTS, "BANK_LINK_RESEND_TOO_SOON",
                        "Vui lòng chờ " + Math.max(1, availableAt.getEpochSecond() - now.getEpochSecond())
                                + " giây rồi gửi lại mã.");
            }
            String otp = newOtp();
            request.resend(otpHash(request.getLinkRequestId(), otp), now.plus(properties.otpTtl()), now);
            linkRequestRepository.save(request);
            sendOtp(request, bankDirectory.find(request.getBankCode()).orElse(null), otp, now);
            return toChallenge(request);
        });
    }

    @Override
    public BankAccountResponse confirm(UUID linkRequestId, ConfirmBankLinkRequest body) {
        String ownerId = SecurityUtils.getCurrentUserId();
        // Lần nhập sai phải được ghi lại (commit) rồi mới báo lỗi, nên transaction trả kết quả thay vì ném bên trong.
        ConfirmOutcome outcome = transactionTemplate.execute(status -> confirmLocked(linkRequestId, ownerId, body.otp()));
        if (outcome.account() != null) {
            return toResponse(outcome.account());
        }
        throw outcome.error();
    }

    private ConfirmOutcome confirmLocked(UUID linkRequestId, String ownerId, String otp) {
        PaymentBankLinkRequest request = lockOwnRequest(linkRequestId, ownerId);
        Instant now = clock.instant();
        switch (request.getStatus()) {
            case CONFIRMED -> {
                // Bấm xác nhận hai lần: trả lại tài khoản đã liên kết thay vì báo lỗi.
                return bankAccountRepository.findActiveByOwner(ownerId).stream()
                        .filter(account -> account.getLinkRequestId().equals(linkRequestId)).findFirst()
                        .map(ConfirmOutcome::linked)
                        .orElseGet(() -> ConfirmOutcome.failed(notPending(request.getStatus())));
            }
            case LOCKED, EXPIRED -> {
                return ConfirmOutcome.failed(notPending(request.getStatus()));
            }
            case PENDING -> {
                // tiếp tục bên dưới
            }
        }
        if (request.isExpired(now)) {
            request.expire(now);
            linkRequestRepository.save(request);
            return ConfirmOutcome.failed(notPending(BankLinkRequestStatus.EXPIRED));
        }
        if (!MessageDigest.isEqual(otpHash(linkRequestId, otp).getBytes(StandardCharsets.US_ASCII),
                request.getOtpHash().getBytes(StandardCharsets.US_ASCII))) {
            int left = request.failAttempt(properties.otpMaxAttempts(), now);
            linkRequestRepository.save(request);
            return ConfirmOutcome.failed(left == 0 ? notPending(BankLinkRequestStatus.LOCKED)
                    : new BusinessException(HttpStatus.UNPROCESSABLE_ENTITY, "BANK_LINK_OTP_INVALID",
                            "Mã xác thực không đúng. Bạn còn " + left + " lần thử."));
        }

        List<PaymentBankAccount> active = bankAccountRepository.findActiveByOwnerForUpdate(ownerId);
        requireRoomFor(active, request.getBankCode(), request.getAccountFingerprint());
        request.confirm(now);
        linkRequestRepository.save(request);
        PaymentBankAccount account = bankAccountRepository.saveAndFlush(
                PaymentBankAccount.link(UUID.randomUUID(), request, active.isEmpty(), now));
        log.info("Đã liên kết ngân hàng: bankAccountId={}, bank={}", account.getBankAccountId(), account.getBankCode());
        return ConfirmOutcome.linked(account);
    }

    @Override
    public BankAccountResponse setDefault(UUID bankAccountId) {
        String ownerId = SecurityUtils.getCurrentUserId();
        return transactionTemplate.execute(status -> {
            List<PaymentBankAccount> active = bankAccountRepository.findActiveByOwnerForUpdate(ownerId);
            PaymentBankAccount target = findAmong(active, bankAccountId);
            Instant now = clock.instant();
            // Bỏ mặc định cũ và ghi xuống trước khi đặt mặc định mới: index chỉ cho một dòng is_default mỗi người.
            active.stream().filter(account -> account != target && account.isDefaultAccount()).forEach(account -> {
                account.markDefault(false, now);
                bankAccountRepository.saveAndFlush(account);
            });
            target.markDefault(true, now);
            return toResponse(bankAccountRepository.saveAndFlush(target));
        });
    }

    @Override
    public void remove(UUID bankAccountId) {
        String ownerId = SecurityUtils.getCurrentUserId();
        transactionTemplate.executeWithoutResult(status -> {
            List<PaymentBankAccount> active = bankAccountRepository.findActiveByOwnerForUpdate(ownerId);
            PaymentBankAccount target = findAmong(active, bankAccountId);
            boolean wasDefault = target.isDefaultAccount();
            Instant now = clock.instant();
            target.remove(now);
            bankAccountRepository.saveAndFlush(target);
            if (wasDefault) {
                // Danh sách đã xếp mới nhất trước: tài khoản liên kết gần nhất còn lại thành mặc định.
                active.stream().filter(PaymentBankAccount::isActive).findFirst().ifPresent(next -> {
                    next.markDefault(true, now);
                    bankAccountRepository.saveAndFlush(next);
                });
            }
            log.info("Đã huỷ liên kết ngân hàng: bankAccountId={}", bankAccountId);
        });
    }

    @Override
    public LinkedDestination destinationFor(String ownerId, UUID bankAccountId) {
        PaymentBankAccount account = bankAccountRepository.findByBankAccountId(bankAccountId)
                .filter(value -> value.getOwnerId().equals(ownerId) && value.isActive())
                .orElseThrow(() -> new BusinessException(HttpStatus.UNPROCESSABLE_ENTITY,
                        "WITHDRAWAL_BANK_ACCOUNT_INVALID", "Tài khoản nhận tiền không còn liên kết. Hãy chọn tài khoản "
                                + "khác."));
        return new LinkedDestination(account.getBankCode(),
                accountNumberCipher.decrypt(account.getAccountNumberEncrypted()), account.getAccountHolderName());
    }

    // ── Kiểm tra dùng chung ───────────────────────────────────────────────

    private BankInfo requireBank(String code) {
        return bankDirectory.find(code).orElseThrow(() -> new BusinessException(HttpStatus.UNPROCESSABLE_ENTITY,
                "BANK_UNSUPPORTED", "Ngân hàng này chưa được hỗ trợ"));
    }

    private UserIdentityClient.CurrentUser requireVerifiedUser() {
        UserIdentityClient.CurrentUser user = userIdentityClient.currentUser();
        if (!user.ekycVerified() || user.fullName() == null || user.fullName().isBlank()) {
            throw new BusinessException(HttpStatus.FORBIDDEN, "BANK_LINK_KYC_REQUIRED",
                    "Bạn cần hoàn tất xác minh danh tính (eKYC) trước khi liên kết ngân hàng.");
        }
        return user;
    }

    private String lookUpHolder(BankInfo bank, String accountNumber, String ownerName) {
        BankAccountProvider.Inquiry inquiry;
        try {
            inquiry = bankAccountProvider.inquire(bank.code(), accountNumber, ownerName);
        } catch (BankAccountProvider.BankUnavailableException exception) {
            log.warn("Ngân hàng không phản hồi tra cứu: bank={}, provider={}", bank.code(), bankAccountProvider.name());
            throw new BusinessException(HttpStatus.SERVICE_UNAVAILABLE, "BANK_UNAVAILABLE",
                    bank.shortName() + " chưa phản hồi. Vui lòng thử lại sau ít phút.");
        }
        return switch (inquiry.outcome()) {
            case FOUND -> inquiry.holderName();
            case NOT_FOUND -> throw new BusinessException(HttpStatus.UNPROCESSABLE_ENTITY, "BANK_ACCOUNT_NOT_EXIST",
                    "Không tìm thấy tài khoản này tại " + bank.shortName() + ". Kiểm tra lại số tài khoản.");
            case LOCKED -> throw new BusinessException(HttpStatus.UNPROCESSABLE_ENTITY, "BANK_ACCOUNT_LOCKED",
                    "Tài khoản đang bị khóa hoặc phong tỏa nên không nhận được tiền.");
        };
    }

    private void requireRoomFor(List<PaymentBankAccount> active, String bankCode, String fingerprint) {
        boolean duplicate = active.stream().anyMatch(account -> account.getBankCode().equals(bankCode)
                && account.getAccountFingerprint().equals(fingerprint));
        if (duplicate) {
            throw new BusinessException(HttpStatus.CONFLICT, "BANK_ACCOUNT_ALREADY_LINKED",
                    "Tài khoản này đã được liên kết.");
        }
        if (active.size() >= properties.maxAccounts()) {
            throw new BusinessException(HttpStatus.UNPROCESSABLE_ENTITY, "BANK_ACCOUNT_LIMIT",
                    "Bạn đã liên kết tối đa " + properties.maxAccounts() + " tài khoản. Huỷ một tài khoản để thêm mới.");
        }
    }

    private Optional<BankLinkChallengeResponse> replay(String ownerId, String key, String bankCode,
            String fingerprint) {
        return linkRequestRepository.findByOwnerIdAndIdempotencyKey(ownerId, key).map(existing -> {
            if (!existing.getBankCode().equals(bankCode) || !existing.getAccountFingerprint().equals(fingerprint)) {
                throw new BusinessException(HttpStatus.CONFLICT, "BANK_LINK_IDEMPOTENCY_CONFLICT",
                        "Idempotency-Key đã được dùng cho một yêu cầu liên kết khác");
            }
            return toChallenge(existing);
        });
    }

    private PaymentBankLinkRequest lockOwnRequest(UUID linkRequestId, String ownerId) {
        // Yêu cầu của người khác trả 404 như không tồn tại, không để lộ mã hợp lệ.
        return linkRequestRepository.findByLinkRequestIdForUpdate(linkRequestId)
                .filter(value -> value.getOwnerId().equals(ownerId))
                .orElseThrow(() -> new BusinessException(HttpStatus.NOT_FOUND, "BANK_LINK_REQUEST_NOT_FOUND",
                        "Không tìm thấy yêu cầu liên kết"));
    }

    private static void requirePending(PaymentBankLinkRequest request) {
        if (request.getStatus() != BankLinkRequestStatus.PENDING) {
            throw notPending(request.getStatus());
        }
    }

    private static BusinessException notPending(BankLinkRequestStatus status) {
        return switch (status) {
            case LOCKED -> new BusinessException(HttpStatus.UNPROCESSABLE_ENTITY, "BANK_LINK_LOCKED",
                    "Bạn đã nhập sai mã quá số lần cho phép. Hãy bắt đầu liên kết lại.");
            case EXPIRED -> new BusinessException(HttpStatus.UNPROCESSABLE_ENTITY, "BANK_LINK_OTP_EXPIRED",
                    "Mã xác thực đã hết hạn. Hãy gửi lại mã hoặc bắt đầu liên kết lại.");
            case CONFIRMED, PENDING -> new BusinessException(HttpStatus.CONFLICT, "BANK_LINK_NOT_PENDING",
                    "Yêu cầu liên kết này đã được xử lý.");
        };
    }

    private static PaymentBankAccount findAmong(List<PaymentBankAccount> active, UUID bankAccountId) {
        return active.stream().filter(account -> account.getBankAccountId().equals(bankAccountId)).findFirst()
                .orElseThrow(() -> new BusinessException(HttpStatus.NOT_FOUND, "BANK_ACCOUNT_NOT_FOUND",
                        "Không tìm thấy tài khoản đã liên kết"));
    }

    private static String requireKey(String value) {
        if (value == null || value.isBlank() || value.length() > 150) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "IDEMPOTENCY_KEY_REQUIRED",
                    "Liên kết ngân hàng cần Idempotency-Key (tối đa 150 ký tự)");
        }
        return value;
    }

    // ── OTP ───────────────────────────────────────────────────────────────

    private String newOtp() {
        StringBuilder otp = new StringBuilder(OTP_DIGITS);
        for (int i = 0; i < OTP_DIGITS; i++) {
            otp.append(random.nextInt(10));
        }
        return otp.toString();
    }

    /** Dấu HMAC gắn với mã yêu cầu: cùng một mã OTP ở hai yêu cầu cho hai dấu khác nhau. */
    private String otpHash(UUID linkRequestId, String otp) {
        return accountNumberCipher.fingerprint("bank-link-otp:" + linkRequestId + ":" + otp);
    }

    /**
     * Ngân hàng giả lập "gửi SMS" bằng thông báo FINORA, qua outbox trong cùng transaction với yêu cầu. Ngân hàng thật tự
     * gửi OTP tới số điện thoại khách hàng nên không phát event.
     */
    private void sendOtp(PaymentBankLinkRequest request, BankInfo bank, String otp, Instant now) {
        if (!"mock".equalsIgnoreCase(properties.provider())) {
            return;
        }
        BankLinkOtpEventData event = new BankLinkOtpEventData(request.getLinkRequestId(), request.getOwnerId(),
                request.getBankCode(), bank == null ? request.getBankCode() : bank.shortName(),
                request.getAccountNumberMasked(), otp, request.getOtpExpiresAt(), now);
        try {
            outboxEventRepository.save(PaymentOutboxEvent.pending(request.getLinkRequestId().toString(),
                    OTP_EVENT_TYPE, objectMapper.writeValueAsString(event), now));
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Không serialize được bank link OTP event", exception);
        }
    }

    // ── Chuyển sang response ──────────────────────────────────────────────

    private BankAccountResponse toResponse(PaymentBankAccount account) {
        return BankAccountResponse.from(account, bankDirectory.find(account.getBankCode()).orElse(null));
    }

    private BankLinkChallengeResponse toChallenge(PaymentBankLinkRequest request) {
        return new BankLinkChallengeResponse(request.getLinkRequestId(), request.getBankCode(),
                bankDirectory.displayName(request.getBankCode()), request.getAccountNumberMasked(),
                request.getAccountHolderName(), request.getOtpExpiresAt(),
                request.getOtpSentAt().plus(properties.otpResendCooldown()),
                Math.max(0, properties.otpMaxResends() - request.getResendCount()));
    }

    private record ConfirmOutcome(PaymentBankAccount account, BusinessException error) {
        static ConfirmOutcome linked(PaymentBankAccount account) {
            return new ConfirmOutcome(account, null);
        }

        static ConfirmOutcome failed(BusinessException error) {
            return new ConfirmOutcome(null, error);
        }
    }
}
