package com.finora.payment.service.bankaccount.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.finora.common.exception.BusinessException;
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
import com.finora.payment.integration.bank.client.MockBankAccountProvider;
import com.finora.payment.integration.user.client.UserIdentityClient;
import com.finora.payment.repository.PaymentOutboxEventRepository;
import com.finora.payment.repository.bankaccount.PaymentBankAccountRepository;
import com.finora.payment.repository.bankaccount.PaymentBankLinkRequestRepository;
import com.finora.payment.service.bank.BankDirectory;
import com.finora.payment.service.bankaccount.BankAccountService;
import com.finora.payment.util.AccountNumberCipher;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * BL-001 với ngân hàng giả lập thật và kho lưu trong bộ nhớ: chỉ người đã eKYC liên kết được, chỉ tài khoản đứng tên
 * mình, OTP sai bị đếm và khóa, OTP hết hạn bị từ chối, idempotency theo khóa PIN, và luôn đúng một tài khoản mặc định.
 */
class BankAccountServiceImplTest {

    private static final String OWNER = "42";
    private static final String KEY = "AES-dev-key-for-unit-tests-32b!!";
    private static final String OWN_ACCOUNT = "0123456789";

    private final MutableClock clock = new MutableClock(Instant.parse("2026-11-06T09:00:00Z"));
    private final List<PaymentBankLinkRequest> requests = new ArrayList<>();
    private final List<PaymentBankAccount> accounts = new ArrayList<>();
    private final List<PaymentOutboxEvent> outbox = new ArrayList<>();
    private final ObjectMapper objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());
    private final AccountNumberCipher cipher = new AccountNumberCipher(
            java.util.Base64.getEncoder().encodeToString(KEY.getBytes()));
    private UserIdentityClient.CurrentUser user = new UserIdentityClient.CurrentUser(OWNER, "Nguyễn Văn Đức",
            "VERIFIED");
    private BankAccountService service;

    @BeforeEach
    void setUp() {
        Jwt jwt = Jwt.withTokenValue("t").header("alg", "none").subject("sub-1").claim("user_id", OWNER).build();
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwt, List.of()));

        TransactionTemplate transactions = mock(TransactionTemplate.class);
        when(transactions.execute(any())).thenAnswer(invocation ->
                invocation.<TransactionCallback<?>>getArgument(0).doInTransaction(null));
        doAnswer(invocation -> {
            invocation.<Consumer<Object>>getArgument(0).accept(null);
            return null;
        }).when(transactions).executeWithoutResult(any());

        service = new BankAccountServiceImpl(accountRepository(), linkRequestRepository(), outboxRepository(),
                new BankDirectory(objectMapper), new MockBankAccountProvider(), () -> user, cipher,
                new BankLinkProperties("mock", 2, Duration.ofMinutes(5), 3, 2, Duration.ofSeconds(60)),
                transactions, objectMapper, clock);
    }

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void inquiryReturnsTheBankHolderNameAndWhetherItMatchesEkyc() {
        BankAccountInquiryResponse own = service.inquire(new BankAccountInquiryRequest("vcb", OWN_ACCOUNT));
        assertThat(own.accountHolderName()).isEqualTo("NGUYEN VAN DUC");
        assertThat(own.nameMatched()).isTrue();
        assertThat(own.bankName()).isEqualTo("Vietcombank");
        assertThat(own.accountNumberMasked()).isEqualTo("******6789");

        BankAccountInquiryResponse stranger = service.inquire(new BankAccountInquiryRequest("VCB", "9876541111"));
        assertThat(stranger.nameMatched()).isFalse();
    }

    @Test
    void bankOutcomesMapToUserFacingErrors() {
        assertCode(() -> service.inquire(new BankAccountInquiryRequest("VCB", "1234560000")), "BANK_ACCOUNT_NOT_EXIST");
        assertCode(() -> service.inquire(new BankAccountInquiryRequest("VCB", "1234569999")), "BANK_ACCOUNT_LOCKED");
        assertCode(() -> service.inquire(new BankAccountInquiryRequest("VCB", "1234565555")), "BANK_UNAVAILABLE");
        assertCode(() -> service.inquire(new BankAccountInquiryRequest("NOPE", OWN_ACCOUNT)), "BANK_UNSUPPORTED");
    }

    @Test
    void userWithoutEkycCannotLink() {
        user = new UserIdentityClient.CurrentUser(OWNER, "Nguyễn Văn Đức", "MANUAL_REVIEW");
        assertCode(() -> service.inquire(new BankAccountInquiryRequest("VCB", OWN_ACCOUNT)), "BANK_LINK_KYC_REQUIRED");
        assertCode(() -> service.startLink(new StartBankLinkRequest("VCB", OWN_ACCOUNT), "k1"),
                "BANK_LINK_KYC_REQUIRED");
        assertThat(requests).isEmpty();
    }

    @Test
    void accountOfSomeoneElseIsRejectedBeforeAnyOtp() {
        assertCode(() -> service.startLink(new StartBankLinkRequest("VCB", "9876541111"), "k1"),
                "BANK_ACCOUNT_NAME_MISMATCH");
        assertThat(requests).isEmpty();
        assertThat(outbox).isEmpty();
    }

    @Test
    void startLinkStoresOnlyEncryptedDataAndSendsTheOtpThroughTheOutbox() throws Exception {
        BankLinkChallengeResponse challenge = service.startLink(new StartBankLinkRequest("VCB", OWN_ACCOUNT), "k1");

        assertThat(challenge.accountNumberMasked()).isEqualTo("******6789");
        assertThat(challenge.accountHolderName()).isEqualTo("NGUYEN VAN DUC");
        assertThat(challenge.resendsLeft()).isEqualTo(2);
        PaymentBankLinkRequest stored = requests.get(0);
        assertThat(stored.getAccountNumberEncrypted()).startsWith("v1:").doesNotContain(OWN_ACCOUNT);
        assertThat(stored.getOtpHash()).hasSize(64).doesNotContain(otpOf(challenge));
        assertThat(outbox).singleElement().satisfies(event -> {
            assertThat(event.getEventType()).isEqualTo("BankLinkOtpRequested");
            assertThat(event.getPayloadJson()).doesNotContain(OWN_ACCOUNT);
        });

        // Gửi lại cùng khóa PIN: trả yêu cầu cũ, không tạo yêu cầu hay OTP mới.
        assertThat(service.startLink(new StartBankLinkRequest("VCB", OWN_ACCOUNT), "k1").linkRequestId())
                .isEqualTo(challenge.linkRequestId());
        assertThat(requests).hasSize(1);
        assertThat(outbox).hasSize(1);
        assertCode(() -> service.startLink(new StartBankLinkRequest("VCB", "0123456788"), "k1"),
                "BANK_LINK_IDEMPOTENCY_CONFLICT");
    }

    @Test
    void correctOtpLinksTheAccountAsDefault() throws Exception {
        BankLinkChallengeResponse challenge = service.startLink(new StartBankLinkRequest("VCB", OWN_ACCOUNT), "k1");

        BankAccountResponse linked = service.confirm(challenge.linkRequestId(),
                new ConfirmBankLinkRequest(otpOf(challenge)));

        assertThat(linked.defaultAccount()).isTrue();
        assertThat(linked.bankLogoUrl()).isEqualTo("https://cdn.vietqr.io/img/VCB.png");
        assertThat(requests.get(0).getStatus()).isEqualTo(BankLinkRequestStatus.CONFIRMED);
        assertThat(service.list()).extracting(BankAccountResponse::bankAccountId).containsExactly(linked.bankAccountId());
        // Bấm xác nhận lần hai trả lại đúng tài khoản, không liên kết thêm.
        assertThat(service.confirm(challenge.linkRequestId(), new ConfirmBankLinkRequest(otpOf(challenge)))
                .bankAccountId()).isEqualTo(linked.bankAccountId());
        assertThat(accounts).hasSize(1);
        // Liên kết lại đúng tài khoản đó bị chặn ngay từ bước bắt đầu.
        assertCode(() -> service.startLink(new StartBankLinkRequest("VCB", OWN_ACCOUNT), "k2"),
                "BANK_ACCOUNT_ALREADY_LINKED");

        BankAccountService.LinkedDestination destination = service.destinationFor(OWNER, linked.bankAccountId());
        assertThat(destination.accountNumber()).isEqualTo(OWN_ACCOUNT);
        assertCode(() -> service.destinationFor("someone-else", linked.bankAccountId()),
                "WITHDRAWAL_BANK_ACCOUNT_INVALID");
    }

    @Test
    void wrongOtpIsCountedAndLocksTheRequest() {
        BankLinkChallengeResponse challenge = service.startLink(new StartBankLinkRequest("VCB", OWN_ACCOUNT), "k1");
        String wrong = "000000".equals(otpOfQuiet(challenge)) ? "111111" : "000000";

        assertThatThrownBy(() -> service.confirm(challenge.linkRequestId(), new ConfirmBankLinkRequest(wrong)))
                .isInstanceOfSatisfying(BusinessException.class, exception -> {
                    assertThat(exception.getCode()).isEqualTo("BANK_LINK_OTP_INVALID");
                    assertThat(exception.getMessage()).contains("còn 2 lần");
                });
        assertCode(() -> service.confirm(challenge.linkRequestId(), new ConfirmBankLinkRequest(wrong)),
                "BANK_LINK_OTP_INVALID");
        assertCode(() -> service.confirm(challenge.linkRequestId(), new ConfirmBankLinkRequest(wrong)),
                "BANK_LINK_LOCKED");
        // Đã khóa: mã đúng cũng không còn dùng được.
        assertCode(() -> service.confirm(challenge.linkRequestId(), new ConfirmBankLinkRequest(otpOfQuiet(challenge))),
                "BANK_LINK_LOCKED");
        assertThat(accounts).isEmpty();
    }

    @Test
    void expiredOtpIsRejectedAndResendHonoursCooldownAndLimit() throws Exception {
        BankLinkChallengeResponse challenge = service.startLink(new StartBankLinkRequest("VCB", OWN_ACCOUNT), "k1");
        assertCode(() -> service.resendOtp(challenge.linkRequestId()), "BANK_LINK_RESEND_TOO_SOON");

        clock.advance(Duration.ofSeconds(61));
        BankLinkChallengeResponse resent = service.resendOtp(challenge.linkRequestId());
        assertThat(resent.resendsLeft()).isEqualTo(1);
        assertThat(outbox).hasSize(2);
        String newOtp = otpOf(resent);

        clock.advance(Duration.ofMinutes(6));
        assertCode(() -> service.confirm(challenge.linkRequestId(), new ConfirmBankLinkRequest(newOtp)),
                "BANK_LINK_OTP_EXPIRED");
        assertThat(requests.get(0).getStatus()).isEqualTo(BankLinkRequestStatus.EXPIRED);
    }

    @Test
    void defaultMovesWhenChangedOrRemoved() throws Exception {
        BankAccountResponse first = link("VCB", OWN_ACCOUNT, "k1");
        BankAccountResponse second = link("ICB", "5566778899", "k2");
        assertThat(second.defaultAccount()).isFalse();
        // Giới hạn 2 tài khoản trong test.
        assertCode(() -> service.startLink(new StartBankLinkRequest("BIDV", "1122334455"), "k3"), "BANK_ACCOUNT_LIMIT");

        service.setDefault(second.bankAccountId());
        assertThat(service.list()).extracting(BankAccountResponse::bankAccountId, BankAccountResponse::defaultAccount)
                .containsExactly(org.assertj.core.groups.Tuple.tuple(second.bankAccountId(), true),
                        org.assertj.core.groups.Tuple.tuple(first.bankAccountId(), false));

        service.remove(second.bankAccountId());
        assertThat(service.list()).singleElement().satisfies(account -> {
            assertThat(account.bankAccountId()).isEqualTo(first.bankAccountId());
            assertThat(account.defaultAccount()).isTrue();
        });
        assertCode(() -> service.remove(second.bankAccountId()), "BANK_ACCOUNT_NOT_FOUND");
        assertCode(() -> service.destinationFor(OWNER, second.bankAccountId()), "WITHDRAWAL_BANK_ACCOUNT_INVALID");
    }

    // ── helpers ─────────────────────────────────────────────────────────

    private BankAccountResponse link(String bank, String number, String key) throws Exception {
        BankLinkChallengeResponse challenge = service.startLink(new StartBankLinkRequest(bank, number), key);
        return service.confirm(challenge.linkRequestId(), new ConfirmBankLinkRequest(otpOf(challenge)));
    }

    /** OTP mới nhất của yêu cầu, đọc từ event "SMS" như người dùng đọc trong hộp thư. */
    private String otpOf(BankLinkChallengeResponse challenge) throws Exception {
        String otp = null;
        for (PaymentOutboxEvent event : outbox) {
            JsonNode payload = objectMapper.readTree(event.getPayloadJson());
            if (payload.get("linkRequestId").asText().equals(challenge.linkRequestId().toString())) {
                otp = payload.get("otp").asText();
            }
        }
        assertThat(otp).matches("[0-9]{6}");
        return otp;
    }

    private String otpOfQuiet(BankLinkChallengeResponse challenge) {
        try {
            return otpOf(challenge);
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static void assertCode(Executable call, String code) {
        assertThatThrownBy(call::execute).isInstanceOfSatisfying(BusinessException.class,
                exception -> assertThat(exception.getCode()).isEqualTo(code));
    }

    private PaymentBankLinkRequestRepository linkRequestRepository() {
        PaymentBankLinkRequestRepository repository = mock(PaymentBankLinkRequestRepository.class);
        when(repository.saveAndFlush(any())).thenAnswer(invocation -> storeRequest(invocation.getArgument(0)));
        when(repository.save(any())).thenAnswer(invocation -> storeRequest(invocation.getArgument(0)));
        when(repository.findByOwnerIdAndIdempotencyKey(anyString(), anyString())).thenAnswer(invocation ->
                requests.stream().filter(value -> value.getOwnerId().equals(invocation.getArgument(0))
                        && value.getIdempotencyKey().equals(invocation.getArgument(1))).findFirst());
        when(repository.findByLinkRequestIdForUpdate(any())).thenAnswer(invocation -> requests.stream()
                .filter(value -> value.getLinkRequestId().equals(invocation.getArgument(0))).findFirst());
        return repository;
    }

    private PaymentBankLinkRequest storeRequest(PaymentBankLinkRequest value) {
        if (requests.stream().noneMatch(existing -> existing == value)) requests.add(value);
        return value;
    }

    private PaymentBankAccountRepository accountRepository() {
        PaymentBankAccountRepository repository = mock(PaymentBankAccountRepository.class);
        when(repository.saveAndFlush(any())).thenAnswer(invocation -> storeAccount(invocation.getArgument(0)));
        when(repository.findByBankAccountId(any())).thenAnswer(invocation -> accounts.stream()
                .filter(value -> value.getBankAccountId().equals(invocation.getArgument(0))).findFirst());
        when(repository.findActiveByOwner(anyString())).thenAnswer(invocation -> active(invocation.getArgument(0),
                Comparator.comparing(PaymentBankAccount::isDefaultAccount).reversed()
                        .thenComparing(PaymentBankAccount::getLinkedAt, Comparator.reverseOrder())));
        when(repository.findActiveByOwnerForUpdate(anyString())).thenAnswer(invocation ->
                active(invocation.getArgument(0), Comparator.comparing(PaymentBankAccount::getLinkedAt,
                        Comparator.reverseOrder())));
        return repository;
    }

    private PaymentBankAccount storeAccount(PaymentBankAccount value) {
        // Như index một-mặc-định-mỗi-người của PostgreSQL: ghi xuống mà có hai dòng mặc định là lỗi.
        if (accounts.stream().noneMatch(existing -> existing == value)) accounts.add(value);
        assertThat(accounts.stream().filter(PaymentBankAccount::isDefaultAccount).count()).isLessThanOrEqualTo(1);
        return value;
    }

    private List<PaymentBankAccount> active(String ownerId, Comparator<PaymentBankAccount> order) {
        // Mỗi lần liên kết lệch thời gian một chút để thứ tự "mới nhất trước" ổn định.
        clock.advance(Duration.ofMillis(1));
        return accounts.stream().filter(value -> value.getOwnerId().equals(ownerId) && value.isActive())
                .sorted(order).toList();
    }

    private PaymentOutboxEventRepository outboxRepository() {
        PaymentOutboxEventRepository repository = mock(PaymentOutboxEventRepository.class);
        when(repository.save(any())).thenAnswer(invocation -> {
            outbox.add(invocation.getArgument(0));
            return invocation.getArgument(0);
        });
        return repository;
    }

    private static final class MutableClock extends Clock {
        private Instant now;

        MutableClock(Instant now) {
            this.now = now;
        }

        void advance(Duration duration) {
            now = now.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
