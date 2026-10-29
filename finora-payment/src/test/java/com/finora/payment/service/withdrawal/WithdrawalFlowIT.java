package com.finora.payment.service.withdrawal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.finora.common.exception.BusinessException;
import com.finora.payment.config.PayoutProperties;
import com.finora.payment.domain.withdrawal.PaymentWithdrawal;
import com.finora.payment.domain.withdrawal.WithdrawalStatus;
import com.finora.payment.dto.request.CreateWithdrawalRequest;
import com.finora.payment.dto.response.WithdrawalResponse;
import com.finora.payment.integration.payout.PayoutSignature;
import com.finora.payment.repository.withdrawal.PaymentWithdrawalRepository;
import com.finora.payment.service.topup.TopUpService;
import com.finora.payment.service.wallet.WalletQueryService;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * R4 (W3B) trên PostgreSQL thật: trừ ví một lần, callback HMAC, hoàn tiền khi thất bại, hạn mức ngày, idempotency,
 * và các nhánh không rõ kết quả (mất callback, worker chết giữa chừng) chỉ tra cứu chứ không gửi lệnh chi mới.
 * Worker chạy tay; provider giả lập không tự gửi callback để test tự ký và gửi.
 */
@SpringBootTest(properties = {
        "spring.kafka.listener.auto-startup=false",
        "finora.payment.scheduling-enabled=false",
        "finora.payment.payout.mock.callback-enabled=false"
})
@Testcontainers
class WithdrawalFlowIT {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRESQL = new PostgreSQLContainer<>(
            DockerImageName.parse("postgres:17.5-alpine"))
            .withDatabaseName("finora_payment_withdrawal_test")
            .withUsername("finora_test")
            .withPassword("finora_test");

    @Autowired private TopUpService topUpService;
    @Autowired private WalletQueryService walletQueryService;
    @Autowired private WithdrawalService withdrawalService;
    @Autowired private WithdrawalProcessingService processingService;
    @Autowired private AdminWithdrawalService adminService;
    @Autowired private PaymentWithdrawalRepository withdrawals;
    @Autowired private PayoutProperties payoutProperties;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private TransactionTemplate transactionTemplate;
    @Autowired private ObjectMapper objectMapper;

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void withdrawalDebitsTheWalletOnceAndCompletesOnTheSignedCallback() throws Exception {
        String investor = actAsInvestor();
        topUp(new BigDecimal("1000000"));

        WithdrawalResponse created = withdrawalService.request(request("500000", "0123456789"), "w-ok-" + investor);
        WithdrawalResponse replay = withdrawalService.request(request("500000", "0123456789"), "w-ok-" + investor);

        assertThat(replay.withdrawalId()).isEqualTo(created.withdrawalId());
        assertThat(created.status()).isEqualTo(WithdrawalStatus.REQUESTED);
        assertThat(created.accountNumberMasked()).isEqualTo("******6789");
        assertThat(created.accountHolderName()).isEqualTo("NGUYEN VAN A");
        assertThat(available()).isEqualByComparingTo("500000");
        String stored = jdbcTemplate.queryForObject(
                "select account_number_encrypted from payment_withdrawals where withdrawal_id = ?", String.class,
                created.withdrawalId());
        assertThat(stored).startsWith("v1:").doesNotContain("0123456789");

        runWorker(created.withdrawalId());
        PaymentWithdrawal pending = withdrawals.findByWithdrawalId(created.withdrawalId()).orElseThrow();
        assertThat(pending.getStatus()).isEqualTo(WithdrawalStatus.PROVIDER_PENDING);
        assertThat(pending.getProviderReference()).isEqualTo("MOCKPAYOUT-" + created.withdrawalId());

        String body = callbackBody(created.withdrawalId(), "SUCCEEDED", null);
        processingService.handleMockCallback(body, sign(body));
        processingService.handleMockCallback(body, sign(body));

        assertThat(withdrawalService.get(created.withdrawalId()).status()).isEqualTo(WithdrawalStatus.COMPLETED);
        assertThat(available()).isEqualByComparingTo("500000");
        assertThat(outboxCount(created.withdrawalId(), "WithdrawalCompleted")).isEqualTo(1);
    }

    @Test
    void failedPayoutRefundsTheWalletInTheSameTransaction() throws Exception {
        String investor = actAsInvestor();
        topUp(new BigDecimal("300000"));
        WithdrawalResponse created = withdrawalService.request(request("200000", "123450000"), "w-fail-" + investor);
        assertThat(available()).isEqualByComparingTo("100000");

        runWorker(created.withdrawalId());
        String body = callbackBody(created.withdrawalId(), "FAILED", "MOCK_ACCOUNT_INVALID");
        processingService.handleMockCallback(body, sign(body));

        WithdrawalResponse failed = withdrawalService.get(created.withdrawalId());
        assertThat(failed.status()).isEqualTo(WithdrawalStatus.FAILED);
        assertThat(failed.failureCode()).isEqualTo("MOCK_ACCOUNT_INVALID");
        assertThat(available()).isEqualByComparingTo("300000");
        assertThat(outboxCount(created.withdrawalId(), "WithdrawalFailed")).isEqualTo(1);
        // Callback ngược chiều sau khi đã hoàn: không được hoàn tất lệnh đã trả tiền về ví.
        String late = callbackBody(created.withdrawalId(), "SUCCEEDED", null);
        assertThatThrownBy(() -> processingService.handleMockCallback(late, sign(late)))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.getCode()).isEqualTo("WITHDRAWAL_CALLBACK_CONFLICT"));
    }

    @Test
    void forgedCallbackChangesNothing() throws Exception {
        String investor = actAsInvestor();
        topUp(new BigDecimal("300000"));
        WithdrawalResponse created = withdrawalService.request(request("100000", "0123456789"), "w-forged-" + investor);
        runWorker(created.withdrawalId());

        String body = callbackBody(created.withdrawalId(), "SUCCEEDED", null);
        assertThatThrownBy(() -> processingService.handleMockCallback(body, "00" + sign(body).substring(2)))
                .isInstanceOfSatisfying(BusinessException.class, exception -> {
                    assertThat(exception.getCode()).isEqualTo("PAYOUT_CALLBACK_SIGNATURE_INVALID");
                    assertThat(exception.getStatus().value()).isEqualTo(401);
                });
        assertThat(withdrawalService.get(created.withdrawalId()).status()).isEqualTo(WithdrawalStatus.PROVIDER_PENDING);
    }

    @Test
    void policyIsEnforcedBeforeAnyMoneyMoves() {
        String investor = actAsInvestor();
        topUp(new BigDecimal("60000000"));

        assertCode(() -> withdrawalService.request(request("49999", "0123456789"), "w-min-" + investor),
                "WITHDRAWAL_BELOW_MINIMUM");
        assertCode(() -> withdrawalService.request(request("70000000", "0123456789"), "w-max-" + investor),
                "WITHDRAWAL_DAILY_LIMIT");
        withdrawalService.request(request("50000000", "0123456789"), "w-limit-" + investor);
        assertCode(() -> withdrawalService.request(request("50000", "0123456789"), "w-over-" + investor),
                "WITHDRAWAL_DAILY_LIMIT");
        assertCode(() -> withdrawalService.request(request("60000", "0123456789"), "w-limit-" + investor),
                "WITHDRAWAL_IDEMPOTENCY_CONFLICT");
        assertCode(() -> withdrawalService.request(new CreateWithdrawalRequest(new BigDecimal("50000"), "XYZ",
                "0123456789", "Nguyen Van A"), "w-bank-" + investor), "WITHDRAWAL_BANK_UNSUPPORTED");
        assertThat(available()).isEqualByComparingTo("10000000");
        assertThat(withdrawalService.policy().remainingToday()).isEqualByComparingTo("0");
    }

    @Test
    void insufficientBalanceIsRejected() {
        String borrower = actAsBorrower();
        topUp(new BigDecimal("100000"));

        assertCode(() -> withdrawalService.request(request("150000", "0123456789"), "w-poor-" + borrower),
                "WITHDRAWAL_INSUFFICIENT_BALANCE");
        assertThat(available()).isEqualByComparingTo("100000");
    }

    @Test
    void lostCallbackIsResolvedByLookingUpTheProvider() {
        String investor = actAsInvestor();
        topUp(new BigDecimal("300000"));
        WithdrawalResponse created = withdrawalService.request(request("100000", "0123456789"), "w-lost-" + investor);
        runWorker(created.withdrawalId());

        // Callback không tới trước hạn chờ: worker tra cứu provider theo mã lệnh.
        makeDue(created.withdrawalId());
        runWorker(created.withdrawalId());

        PaymentWithdrawal value = withdrawals.findByWithdrawalId(created.withdrawalId()).orElseThrow();
        assertThat(value.getStatus()).isEqualTo(WithdrawalStatus.COMPLETED);
        assertThat(value.getAttemptCount()).isEqualTo(1);
    }

    @Test
    void workerDyingMidSubmitIsReconciledAndOnlyResubmittedWhenTheProviderHasNothing() {
        String investor = actAsInvestor();
        topUp(new BigDecimal("300000"));
        WithdrawalResponse created = withdrawalService.request(request("100000", "0123456789"), "w-crash-" + investor);
        Long id = withdrawals.findByWithdrawalId(created.withdrawalId()).orElseThrow().getId();

        // Worker nhận việc rồi chết trước khi gọi provider: lệnh nằm PROCESSING tới hết lease.
        WithdrawalProcessingService.Work work = transactionTemplate.execute(status -> processingService.claim(id));
        assertThat(work).isNotNull();
        makeDue(created.withdrawalId());
        runWorker(created.withdrawalId());

        // Hết lease → đối soát → provider không có lệnh (chưa từng nhận) → gửi lại đúng mã lệnh.
        PaymentWithdrawal resubmitted = withdrawals.findByWithdrawalId(created.withdrawalId()).orElseThrow();
        assertThat(resubmitted.getStatus()).isEqualTo(WithdrawalStatus.REQUESTED);
        assertThat(resubmitted.getErrorCode()).isEqualTo("WITHDRAWAL_PROCESSING_INTERRUPTED");
        runWorker(created.withdrawalId());
        PaymentWithdrawal pending = withdrawals.findByWithdrawalId(created.withdrawalId()).orElseThrow();
        assertThat(pending.getStatus()).isEqualTo(WithdrawalStatus.PROVIDER_PENDING);
        assertThat(pending.getAttemptCount()).isEqualTo(2);
        assertThat(available()).isEqualByComparingTo("200000");
    }

    @Test
    void operationsCanRecheckOnlyAnExhaustedReconciliation() {
        String investor = actAsInvestor();
        topUp(new BigDecimal("300000"));
        WithdrawalResponse created = withdrawalService.request(request("100000", "0123456789"), "w-admin-" + investor);
        jdbcTemplate.update("""
                update payment_withdrawals set status = 'RECONCILIATION_REQUIRED', next_attempt_at = null,
                    error_code = 'WITHDRAWAL_RESULT_UNKNOWN' where withdrawal_id = ?""", created.withdrawalId());

        assertCode(() -> adminService.recheck(created.withdrawalId()), "PERMISSION_REQUIRED");
        actAs("ADMIN-1", "ROLE_ADMIN");
        assertThat(adminService.list(0, 100).getContent())
                .anySatisfy(item -> assertThat(item.withdrawalId()).isEqualTo(created.withdrawalId()));
        assertThat(adminService.recheck(created.withdrawalId()).nextAttemptAt()).isNotNull();
        assertCode(() -> adminService.recheck(UUID.randomUUID()), "WITHDRAWAL_NOT_FOUND");
    }

    private void runWorker(UUID withdrawalId) {
        Long id = withdrawals.findByWithdrawalId(withdrawalId).orElseThrow().getId();
        processingService.execute(id);
    }

    private void makeDue(UUID withdrawalId) {
        jdbcTemplate.update("update payment_withdrawals set next_attempt_at = now() - interval '1 second' "
                + "where withdrawal_id = ?", withdrawalId);
    }

    private String callbackBody(UUID withdrawalId, String status, String errorCode) throws Exception {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("withdrawalId", withdrawalId.toString());
        payload.put("reference", "MOCKPAYOUT-" + withdrawalId);
        payload.put("status", status);
        payload.put("errorCode", errorCode);
        payload.put("errorMessage", errorCode == null ? null : "Tài khoản nhận không hợp lệ (provider giả lập)");
        return objectMapper.writeValueAsString(payload);
    }

    private String sign(String body) {
        return PayoutSignature.sign(body, payoutProperties.mock().callbackSecret());
    }

    private int outboxCount(UUID withdrawalId, String type) {
        Integer count = jdbcTemplate.queryForObject(
                "select count(*) from payment_outbox_events where aggregate_id = ? and event_type = ?",
                Integer.class, withdrawalId.toString(), type);
        return count == null ? 0 : count;
    }

    private void topUp(BigDecimal amount) {
        var created = topUpService.create(amount, "topup-" + UUID.randomUUID());
        topUpService.completeMock(created.topUpId());
    }

    private BigDecimal available() {
        return walletQueryService.currentBalance().available();
    }

    private static CreateWithdrawalRequest request(String amount, String accountNumber) {
        return new CreateWithdrawalRequest(new BigDecimal(amount), "VCB", accountNumber, "  Nguyen  Van a ");
    }

    private static void assertCode(Runnable action, String code) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(BusinessException.class,
                exception -> assertThat(exception.getCode()).isEqualTo(code));
    }

    private static String actAsInvestor() {
        return actAs("INV-W-" + UUID.randomUUID().toString().substring(0, 8), "ROLE_INVESTOR");
    }

    private static String actAsBorrower() {
        return actAs("BOR-W-" + UUID.randomUUID().toString().substring(0, 8), "ROLE_BORROWER");
    }

    private static String actAs(String userId, String role) {
        Jwt jwt = Jwt.withTokenValue("test-token").header("alg", "none").subject(userId).claim("user_id", userId)
                .build();
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(
                jwt, List.of(new SimpleGrantedAuthority(role)), userId));
        return userId;
    }
}
