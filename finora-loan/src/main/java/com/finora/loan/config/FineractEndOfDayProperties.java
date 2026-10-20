package com.finora.loan.config;

import java.time.Duration;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Giám sát job cuối ngày của Fineract (W3E, D57; D25).
 *
 * <p>DPD, nhóm nợ và số tiền khắc phục quá hạn của FINORA lấy từ {@code summary.totalOverdue} và lịch của Fineract;
 * Fineract chỉ cập nhật chúng khi job "Recalculate Interest For Loans" ({@code LA_RINT}) và "Update Loan Arrears
 * Ageing" ({@code LA_ARAG}) chạy (production: cron 00:01). Job không chạy thì DPD đứng yên mà không có lỗi nào khác.</p>
 *
 * @param enabled         tắt hẳn việc kiểm tra (health báo UNKNOWN, không gọi Fineract)
 * @param staleAfter      lần chạy thành công gần nhất cũ hơn mức này thì health DOWN; mặc định 26 giờ = một chu kỳ
 *                        ngày + 2 giờ dư cho job chạy chậm hoặc lệch giờ cron
 * @param refreshInterval khoảng cách giữa hai lần đọc Fineract; tuổi lần chạy vẫn được tính lại theo giờ thật mỗi lần
 *                        health/gauge được đọc
 * @param jobs            short name các job bắt buộc, theo đúng thứ tự cron
 * @param historyLimit    số lần chạy gần nhất đọc khi lần chạy cuối không thành công, để tìm lần thành công trước đó
 */
@ConfigurationProperties(prefix = "finora.loan.operations.fineract-eod")
public record FineractEndOfDayProperties(
        @DefaultValue("true") boolean enabled,
        @DefaultValue("26h") Duration staleAfter,
        @DefaultValue("5m") Duration refreshInterval,
        @DefaultValue({"LA_RINT", "LA_ARAG"}) List<String> jobs,
        @DefaultValue("50") int historyLimit
) {

    public FineractEndOfDayProperties {
        staleAfter = requirePositive(staleAfter, "staleAfter");
        refreshInterval = requirePositive(refreshInterval, "refreshInterval");
        jobs = jobs == null ? List.of() : jobs.stream()
                .filter(job -> job != null && !job.isBlank())
                .map(String::trim)
                .distinct()
                .toList();
        if (enabled && jobs.isEmpty()) {
            throw new IllegalArgumentException("finora.loan.operations.fineract-eod.jobs không được rỗng khi bật kiểm tra");
        }
        if (historyLimit < 1 || historyLimit > 200) {
            throw new IllegalArgumentException("finora.loan.operations.fineract-eod.historyLimit phải trong 1..200");
        }
    }

    private static Duration requirePositive(Duration value, String field) {
        if (value == null || value.isZero() || value.isNegative()) {
            throw new IllegalArgumentException("finora.loan.operations.fineract-eod." + field + " phải lớn hơn 0");
        }
        return value;
    }
}
