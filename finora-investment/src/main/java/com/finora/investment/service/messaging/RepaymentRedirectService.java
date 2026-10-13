package com.finora.investment.service.messaging;

import com.finora.investment.client.PaymentClient;
import com.finora.investment.client.PaymentTransferResult;
import com.finora.investment.domain.secondary.NoteRepaymentRedirect;
import com.finora.investment.repository.NoteRepaymentRedirectRepository;
import java.time.Clock;
import java.time.Duration;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Chuyển lại cho chủ hiện tại các khoản trả nợ Payment đã chia nhầm cho chủ cũ của Note
 * ({@link NoteRepaymentRedirect}). Gọi Payment ngoài transaction, ghi kết quả bằng transaction ngắn.
 */
@Slf4j
@Service
public class RepaymentRedirectService {

    /** Người bán đã tiêu tiền: vẫn là khoản phải thu, thử lại chậm thay vì dừng hẳn. */
    private static final Set<String> RETRY_LATER_CODES =
            Set.of("PAYMENT_INSUFFICIENT_BALANCE", "INSUFFICIENT_BALANCE");
    private static final Duration BASE_BACKOFF = Duration.ofSeconds(30);
    private static final Duration MAX_BACKOFF = Duration.ofHours(1);

    private final NoteRepaymentRedirectRepository redirects;
    private final PaymentClient paymentClient;
    private final TransactionTemplate transactionTemplate;
    private final Clock clock;

    public RepaymentRedirectService(NoteRepaymentRedirectRepository redirects, PaymentClient paymentClient,
            PlatformTransactionManager transactionManager, Clock clock) {
        this.redirects = redirects;
        this.paymentClient = paymentClient;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        this.clock = clock;
    }

    /** @return số khoản đã chuyển xong trong lượt này */
    public int settleDue(int batchSize) {
        int completed = 0;
        for (NoteRepaymentRedirect due : redirects.findDue(clock.instant(), PageRequest.of(0, batchSize))) {
            PaymentTransferResult result = paymentClient.transferBetweenInvestors(
                    due.getFromInvestorId(), due.getToInvestorId(), due.getAmount(), due.getRedirectReference());
            transactionTemplate.executeWithoutResult(status -> {
                NoteRepaymentRedirect fresh = redirects.findById(due.getId()).orElseThrow();
                var now = clock.instant();
                if (result.isSuccess()) {
                    fresh.complete(result.getTransferReference(), now);
                } else if (result.isRetryable() || RETRY_LATER_CODES.contains(result.getErrorCode())) {
                    fresh.retryLater(result.getErrorCode(), backoff(fresh.getAttempts()), now);
                } else {
                    fresh.fail(result.getErrorCode(), now);
                    log.error("Payment từ chối chuyển lại khoản trả nợ, cần đối soát: redirectReference={}, errorCode={}",
                            fresh.getRedirectReference(), result.getErrorCode());
                }
            });
            if (result.isSuccess()) {
                completed++;
                log.info("Đã chuyển lại khoản trả nợ cho chủ Note hiện tại: redirectReference={}",
                        due.getRedirectReference());
            }
        }
        return completed;
    }

    private Duration backoff(int attempts) {
        Duration next = BASE_BACKOFF.multipliedBy(1L << Math.min(attempts, 16));
        return next.compareTo(MAX_BACKOFF) > 0 ? MAX_BACKOFF : next;
    }
}
