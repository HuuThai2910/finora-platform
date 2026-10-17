package com.finora.payment.service.withdrawal;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.finora.common.exception.BusinessException;
import com.finora.payment.config.PayoutProperties;
import com.finora.payment.config.WithdrawalProperties;
import com.finora.payment.domain.outbox.PaymentOutboxEvent;
import com.finora.payment.domain.withdrawal.PaymentWithdrawal;
import com.finora.payment.domain.withdrawal.WithdrawalStatus;
import com.finora.payment.integration.payout.PayoutProvider;
import com.finora.payment.integration.payout.PayoutSignature;
import com.finora.payment.messaging.WithdrawalEventData;
import com.finora.payment.repository.PaymentOutboxEventRepository;
import com.finora.payment.repository.withdrawal.PaymentWithdrawalRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Đưa lệnh rút từ {@code REQUESTED} tới kết quả cuối (R4, W3B). Mỗi bước khóa dòng lệnh trong transaction ngắn, gọi
 * provider ngoài transaction, rồi khóa lại để áp kết quả — giống lệnh giải ngân. Quy tắc tiền (D41, D42):
 * <ul>
 *   <li>Không rõ provider đã chi hay chưa (timeout, worker chết giữa chừng, mất callback) thì chỉ tra cứu theo mã lệnh,
 *       không gửi lệnh chi mới.</li>
 *   <li>Provider khẳng định chưa từng nhận lệnh mới gửi lại, với đúng mã lệnh cũ.</li>
 *   <li>Thất bại chắc chắn hoàn tiền trong cùng transaction chuyển {@code FAILED}.</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WithdrawalProcessingService {

    static final List<WithdrawalStatus> OPEN = List.of(WithdrawalStatus.REQUESTED, WithdrawalStatus.PROCESSING,
            WithdrawalStatus.PROVIDER_PENDING, WithdrawalStatus.RECONCILIATION_REQUIRED);

    private final PaymentWithdrawalRepository withdrawals;
    private final PaymentOutboxEventRepository outboxRepository;
    private final WithdrawalLedger ledger;
    private final AccountNumberCipher cipher;
    private final PayoutProvider provider;
    private final WithdrawalProperties properties;
    private final PayoutProperties payoutProperties;
    private final ObjectMapper objectMapper;
    private final TransactionTemplate transactionTemplate;
    private final Clock clock;

    enum Step { SUBMIT, LOOKUP }

    record Work(Long id, UUID withdrawalId, Step step, PayoutProvider.PayoutCommand command) {
    }

    public List<Long> dueIds() {
        return withdrawals.findDueIds(OPEN, clock.instant(), PageRequest.of(0, 20));
    }

    public void execute(Long id) {
        Work work = transactionTemplate.execute(status -> claim(id));
        if (work == null) return;
        if (work.step() == Step.SUBMIT) {
            PayoutProvider.Submission result;
            try {
                result = provider.submit(work.command());
            } catch (RuntimeException exception) {
                // Lệnh có thể đã tới provider mà mất phản hồi: không đoán, chuyển đối soát.
                log.warn("Gửi lệnh chi không rõ kết quả: withdrawalId={}, error={}", work.withdrawalId(),
                        exception.getClass().getSimpleName());
                result = null;
            }
            PayoutProvider.Submission submission = result;
            transactionTemplate.executeWithoutResult(status -> applySubmission(id, submission));
        } else {
            PayoutProvider.Status found;
            try {
                found = provider.lookup(work.withdrawalId());
            } catch (RuntimeException exception) {
                log.warn("Tra cứu lệnh chi lỗi: withdrawalId={}, error={}", work.withdrawalId(),
                        exception.getClass().getSimpleName());
                found = null;
            }
            PayoutProvider.Status status = found;
            transactionTemplate.executeWithoutResult(tx -> applyLookup(id, status));
        }
    }

    Work claim(Long id) {
        PaymentWithdrawal value = withdrawals.findByIdForUpdate(id).orElseThrow();
        Instant now = clock.instant();
        if (!value.isDue(now)) return null;
        switch (value.getStatus()) {
            case REQUESTED -> {
                if (value.getAttemptCount() >= properties.maxSubmitAttempts()) {
                    value.requireReconciliation("WITHDRAWAL_SUBMIT_ATTEMPTS_EXHAUSTED",
                            "Đã gửi provider " + value.getAttemptCount() + " lần mà chưa có lệnh chi; cần vận hành",
                            now, null);
                    return null;
                }
                value.startProcessing(now, now.plus(properties.processingLease()));
                PayoutProvider.PayoutCommand command = new PayoutProvider.PayoutCommand(value.getWithdrawalId(),
                        value.getAmount(), value.getCurrency(), value.getBankCode(),
                        cipher.decrypt(value.getAccountNumberEncrypted()), value.getAccountHolderName());
                return new Work(value.getId(), value.getWithdrawalId(), Step.SUBMIT, command);
            }
            case PROCESSING -> {
                // isDue với PROCESSING là lease đã hết: worker trước chết khi đang gọi provider.
                log.warn("Lệnh rút hết lease khi đang gọi provider, chuyển đối soát: withdrawalId={}",
                        value.getWithdrawalId());
                value.requireReconciliation("WITHDRAWAL_PROCESSING_INTERRUPTED",
                        "Worker dừng khi đang gọi provider; tra cứu theo mã lệnh trước khi xử lý tiếp", now, now);
                return new Work(value.getId(), value.getWithdrawalId(), Step.LOOKUP, null);
            }
            case PROVIDER_PENDING, RECONCILIATION_REQUIRED -> {
                return new Work(value.getId(), value.getWithdrawalId(), Step.LOOKUP, null);
            }
            default -> {
                return null;
            }
        }
    }

    void applySubmission(Long id, PayoutProvider.Submission result) {
        PaymentWithdrawal value = withdrawals.findByIdForUpdate(id).orElseThrow();
        // Callback tới trước khi worker lưu, hoặc lease đã hết và lệnh đã sang đối soát: kết quả gọi này không còn dùng.
        if (value.getStatus() != WithdrawalStatus.PROCESSING) return;
        Instant now = clock.instant();
        if (result == null) {
            value.requireReconciliation("WITHDRAWAL_PROVIDER_UNCERTAIN",
                    "Không xác định được provider đã nhận lệnh chi; tra cứu theo mã lệnh", now,
                    now.plus(properties.reconciliationInterval()));
        } else if (result.accepted()) {
            value.providerAccepted(result.reference(), now, now.plus(properties.callbackTimeout()));
        } else {
            failAndRefund(value, result.errorCode(), result.errorMessage(), now);
        }
    }

    void applyLookup(Long id, PayoutProvider.Status status) {
        PaymentWithdrawal value = withdrawals.findByIdForUpdate(id).orElseThrow();
        if (value.getStatus() != WithdrawalStatus.PROVIDER_PENDING
                && value.getStatus() != WithdrawalStatus.RECONCILIATION_REQUIRED) return;
        Instant now = clock.instant();
        if (status == null || status.outcome() == PayoutProvider.Outcome.PENDING) {
            recordPendingCheck(value, now);
            return;
        }
        switch (status.outcome()) {
            case SUCCEEDED -> complete(value, status.reference(), now);
            case FAILED -> failAndRefund(value, status.errorCode(), status.errorMessage(), now);
            case NOT_FOUND -> {
                if (value.getAttemptCount() < properties.maxSubmitAttempts()) {
                    // Provider khẳng định không có lệnh: gửi lại với đúng mã lệnh, provider idempotent theo mã (D42).
                    value.resubmit(now);
                } else {
                    value.requireReconciliation("WITHDRAWAL_NOT_FOUND_AT_PROVIDER",
                            "Provider không có lệnh sau " + value.getAttemptCount() + " lần gửi; cần vận hành", now, null);
                }
            }
            default -> recordPendingCheck(value, now);
        }
    }

    private void recordPendingCheck(PaymentWithdrawal value, Instant now) {
        boolean more = value.getReconciliationAttemptCount() + 1 < properties.reconciliationMaxAttempts();
        if (more) {
            value.recordPendingCheck(now, now.plus(properties.reconciliationInterval()));
        } else {
            log.error("Hết lượt tra cứu tự động lệnh rút, cần vận hành đối soát: withdrawalId={}",
                    value.getWithdrawalId());
            value.requireReconciliation("WITHDRAWAL_RESULT_UNKNOWN",
                    "Hết lượt tra cứu tự động mà provider chưa báo kết quả cuối", now, null);
        }
    }

    /**
     * Callback của provider payout giả lập (D43). Chỉ tồn tại khi provider là {@code mock}; chữ ký sai trả 401 và không
     * đổi gì; callback lặp không ghi/hoàn hai lần; callback mâu thuẫn với kết quả đã chốt trả 409 để vận hành kiểm.
     */
    public void handleMockCallback(String body, String signature) {
        if (!"mock".equals(payoutProperties.provider())) {
            throw new BusinessException(HttpStatus.NOT_FOUND, "PAYOUT_CALLBACK_NOT_FOUND", "Không có callback này");
        }
        if (!PayoutSignature.matches(body, payoutProperties.mock().callbackSecret(), signature)) {
            throw new BusinessException(HttpStatus.UNAUTHORIZED, "PAYOUT_CALLBACK_SIGNATURE_INVALID",
                    "Chữ ký callback không hợp lệ");
        }
        JsonNode payload;
        UUID withdrawalId;
        PayoutProvider.Outcome outcome;
        try {
            payload = objectMapper.readTree(body);
            withdrawalId = UUID.fromString(payload.path("withdrawalId").asText());
            outcome = PayoutProvider.Outcome.valueOf(payload.path("status").asText());
        } catch (JsonProcessingException | IllegalArgumentException exception) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "PAYOUT_CALLBACK_INVALID", "Callback không đúng định dạng");
        }
        String reference = payload.path("reference").asText(null);
        String errorCode = payload.path("errorCode").asText(null);
        String errorMessage = payload.path("errorMessage").asText(null);
        transactionTemplate.executeWithoutResult(status -> {
            PaymentWithdrawal value = withdrawals.findByWithdrawalIdForUpdate(withdrawalId)
                    .orElseThrow(() -> new BusinessException(HttpStatus.NOT_FOUND, "WITHDRAWAL_NOT_FOUND",
                            "Không tìm thấy lệnh rút"));
            Instant now = clock.instant();
            if (value.getStatus().isFinal()) {
                boolean duplicate = (outcome == PayoutProvider.Outcome.SUCCEEDED
                        && value.getStatus() == WithdrawalStatus.COMPLETED)
                        || (outcome == PayoutProvider.Outcome.FAILED && value.getStatus() == WithdrawalStatus.FAILED);
                if (duplicate) return;
                log.error("Callback payout mâu thuẫn kết quả đã chốt: withdrawalId={}, status={}, callback={}",
                        withdrawalId, value.getStatus(), outcome);
                throw new BusinessException(HttpStatus.CONFLICT, "WITHDRAWAL_CALLBACK_CONFLICT",
                        "Callback mâu thuẫn kết quả đã chốt của lệnh rút");
            }
            switch (outcome) {
                case SUCCEEDED -> complete(value, reference, now);
                case FAILED -> failAndRefund(value, errorCode == null ? "PAYOUT_FAILED" : errorCode, errorMessage, now);
                default -> {
                    // PENDING/NOT_FOUND qua callback không chốt gì; worker vẫn tra cứu theo lịch.
                }
            }
        });
    }

    private void complete(PaymentWithdrawal value, String reference, Instant now) {
        if (value.complete(reference, now)) {
            record(value, "WithdrawalCompleted", now);
        }
    }

    private void failAndRefund(PaymentWithdrawal value, String code, String detail, Instant now) {
        UUID refund = ledger.refund(value.getWithdrawalId(), value.getWalletId(), value.getAmount(),
                value.getFeeAmount(), value.getCurrency());
        value.fail(code == null ? "PAYOUT_FAILED" : code, detail, refund, now);
        record(value, "WithdrawalFailed", now);
    }

    private void record(PaymentWithdrawal value, String type, Instant now) {
        WithdrawalEventData data = new WithdrawalEventData(value.getWithdrawalId(), value.getOwnerType().name(),
                value.getOwnerId(), value.getAmount().toPlainString(), value.getCurrency(), value.getBankCode(),
                value.getAccountNumberMasked(), value.getStatus().name(), value.getErrorCode(), now);
        try {
            outboxRepository.save(PaymentOutboxEvent.pending(value.getWithdrawalId().toString(), type,
                    objectMapper.writeValueAsString(data), now));
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Không serialize được withdrawal event", exception);
        }
    }
}
