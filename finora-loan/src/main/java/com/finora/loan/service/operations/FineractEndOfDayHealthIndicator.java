package com.finora.loan.service.operations;

import com.finora.loan.service.operations.FineractEndOfDayStatus.JobFreshness;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.boot.actuate.health.Status;
import org.springframework.stereotype.Component;

/**
 * Health {@code fineractEndOfDay} (W3E, D57): nằm trong nhóm {@code operations} và health tổng, KHÔNG nằm trong
 * liveness/readiness. Job cuối ngày trễ là sự cố dữ liệu servicing cần vận hành xử lý; rút Loan khỏi traffic không sửa
 * được nó mà còn chặn người vay trả nợ.
 *
 * <ul>
 *   <li>{@code UP}: mọi job bắt buộc chạy thành công trong ngưỡng.</li>
 *   <li>{@code DOWN}: job quá ngưỡng/chưa từng thành công ({@code FINERACT_EOD_STALE}) hoặc Fineract thiếu job
 *       ({@code FINERACT_EOD_JOB_MISSING}).</li>
 *   <li>{@code UNKNOWN}: chưa kiểm tra, không đọc được Fineract, thiếu lịch sử, hoặc không áp dụng (provider mock).
 *       UNKNOWN không kéo health tổng xuống.</li>
 * </ul>
 * Chi tiết chỉ gồm mã job, thời điểm và lý do; không có dữ liệu khoản vay hay message lỗi thô của Fineract.
 */
@Component
public class FineractEndOfDayHealthIndicator implements HealthIndicator {

    private final FineractEndOfDayMonitor monitor;

    public FineractEndOfDayHealthIndicator(FineractEndOfDayMonitor monitor) {
        this.monitor = monitor;
    }

    @Override
    public Health health() {
        FineractEndOfDayStatus status = monitor.currentStatus();
        Health.Builder builder = Health.status(statusOf(status.code()))
                .withDetail("reason", "FINERACT_EOD_" + status.code().name())
                .withDetail("message", status.message())
                .withDetail("staleAfter", status.staleAfter().toString());
        if (status.checkedAt() != null) {
            builder.withDetail("checkedAt", status.checkedAt().toString());
        }
        if (status.checkError() != null) {
            builder.withDetail("checkError", status.checkError());
        }
        if (!status.jobs().isEmpty()) {
            builder.withDetail("jobs", jobDetails(status.jobs()));
        }
        return builder.build();
    }

    static Status statusOf(FineractEndOfDayStatus.Code code) {
        return switch (code) {
            case FRESH -> Status.UP;
            case STALE, JOB_MISSING -> Status.DOWN;
            case HISTORY_UNAVAILABLE, CHECK_FAILED, NOT_CHECKED, NOT_APPLICABLE -> Status.UNKNOWN;
        };
    }

    private static List<Map<String, Object>> jobDetails(List<JobFreshness> jobs) {
        List<Map<String, Object>> details = new ArrayList<>(jobs.size());
        for (JobFreshness job : jobs) {
            Map<String, Object> detail = new LinkedHashMap<>();
            detail.put("job", job.shortName());
            detail.put("state", job.state().name());
            detail.put("cronActive", job.active());
            if (job.lastRunStatus() != null) {
                detail.put("lastRunStatus", job.lastRunStatus());
            }
            if (job.lastRunAt() != null) {
                detail.put("lastRunAt", job.lastRunAt().toString());
            }
            if (job.lastSuccessAt() != null) {
                detail.put("lastSuccessAt", job.lastSuccessAt().toString());
                detail.put("lastSuccessAgeHours", job.lastSuccessAge().toHours());
            }
            details.add(detail);
        }
        return details;
    }
}
