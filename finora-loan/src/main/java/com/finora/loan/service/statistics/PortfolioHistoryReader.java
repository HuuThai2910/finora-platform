package com.finora.loan.service.statistics;

import com.finora.loan.domain.servicing.LoanServicingHistory;
import com.finora.loan.integration.fineract.client.FineractIntegrationException;
import com.finora.loan.integration.fineract.client.FineractServicingGateway;
import com.finora.loan.repository.statistics.LoanStatisticsRepository.HistoryLoanRow;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Đọc lịch sử khoản vay từ Fineract cho thống kê dư nợ theo ngày, có cache trong bộ nhớ.
 *
 * <p>Mỗi khoản một lần gọi Fineract; bản đọc được giữ tới khi dấu vân tay projection đổi (có giao dịch,
 * đổi trạng thái hoặc cơ cấu), nên trang Tổng quan mở lại không gọi lại core. Khoản đọc lỗi (core không
 * có khoản đó, externalId không khớp, Fineract tạm lỗi) được nhớ là thiếu trong {@link #FAILURE_RETRY}
 * để không gọi dồn mỗi lần mở trang; thống kê báo số khoản thiếu thay vì đoán số.</p>
 *
 * <p>Cache theo từng instance: chạy nhiều instance thì mỗi instance tự đọc một lần, chấp nhận được với
 * quy mô vài chục khoản vay.</p>
 */
@Component
@Slf4j
public class PortfolioHistoryReader {

    static final Duration FAILURE_RETRY = Duration.ofMinutes(1);

    private final FineractServicingGateway gateway;
    private final Clock clock;
    private final Map<Long, Cached> cache = new ConcurrentHashMap<>();

    public PortfolioHistoryReader(FineractServicingGateway gateway, Clock clock) {
        this.gateway = gateway;
        this.clock = clock;
    }

    /** Lịch sử theo id khoản vay FINORA; khoản không đọc được thì vắng mặt trong map. */
    public Map<Long, LoanServicingHistory> read(List<HistoryLoanRow> loans) {
        Map<Long, LoanServicingHistory> histories = new HashMap<>();
        Instant now = clock.instant();
        for (HistoryLoanRow loan : loans) {
            Cached cached = cache.get(loan.loanId());
            if (cached == null || !cached.usableFor(loan.fingerprint(), now)) {
                cached = load(loan, now);
                cache.put(loan.loanId(), cached);
            }
            if (cached.history() != null) {
                histories.put(loan.loanId(), cached.history());
            }
        }
        return histories;
    }

    private Cached load(HistoryLoanRow loan, Instant now) {
        try {
            LoanServicingHistory history = gateway.readHistory(loan.fineractLoanId());
            if (!loan.contractNumber().equals(history.externalId())) {
                log.warn("Bỏ lịch sử khoản vay vì externalId trên Fineract không khớp: loanId={}", loan.loanId());
                return new Cached(loan.fingerprint(), null, now);
            }
            return new Cached(loan.fingerprint(), history, now);
        } catch (FineractIntegrationException exception) {
            log.warn("Không đọc được lịch sử khoản vay từ Fineract: loanId={}, code={}",
                    loan.loanId(), exception.getCode());
            return new Cached(loan.fingerprint(), null, now);
        }
    }

    private record Cached(String fingerprint, LoanServicingHistory history, Instant loadedAt) {
        boolean usableFor(String currentFingerprint, Instant now) {
            if (!fingerprint.equals(currentFingerprint)) return false;
            return history != null || loadedAt.plus(FAILURE_RETRY).isAfter(now);
        }
    }
}
