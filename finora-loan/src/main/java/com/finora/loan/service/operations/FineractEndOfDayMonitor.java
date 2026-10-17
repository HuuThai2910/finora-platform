package com.finora.loan.service.operations;

import com.finora.common.observability.OperationalRefreshTask;
import com.finora.loan.config.FineractEndOfDayProperties;
import com.finora.loan.integration.fineract.client.FineractIntegrationException;
import com.finora.loan.integration.fineract.client.FineractJobGateway;
import com.finora.loan.integration.fineract.contract.FineractJob;
import com.finora.loan.integration.fineract.contract.FineractJobRun;
import com.finora.loan.service.operations.FineractEndOfDayStatus.Code;
import com.finora.loan.service.operations.FineractEndOfDayStatus.JobFreshness;
import com.finora.loan.service.operations.FineractEndOfDayStatus.JobState;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Giám sát job cuối ngày của Fineract (W3E, D57; D25): DPD, nhóm nợ và số tiền khắc phục quá hạn của FINORA lấy từ
 * {@code summary.totalOverdue} và lịch Fineract, mà Fineract chỉ cập nhật khi {@code LA_RINT} rồi {@code LA_ARAG} chạy.
 * Job không chạy thì DPD đứng yên, không có exception nào; monitor này biến điều đó thành tín hiệu vận hành.
 *
 * <p>Luồng: {@link #refresh()} chạy trên luồng vận hành chung (mặc định 5 phút) đọc {@code GET /jobs}; job nào lần chạy
 * cuối không thành công thì đọc thêm {@code runhistory} để tìm lần thành công trước đó. Kết quả giữ trong bộ nhớ; health
 * và gauge đọc bản này và tính tuổi theo giờ thật tại lúc đọc, nên health không chờ Fineract và vẫn chuyển DOWN đúng lúc
 * vượt ngưỡng dù Fineract đang không trả lời.</p>
 *
 * <p>Tuổi tính theo {@code Clock.systemUTC()}, không dùng clock nghiệp vụ của Loan: thời điểm chạy job là giờ thật của
 * Fineract, còn clock nghiệp vụ có thể bị harness E2E dời ngày (Q12).</p>
 *
 * <p>Không áp dụng khi Loan ghi khoản vay vào provider mock: khi đó không có khoản vay thật trên Fineract để job cập nhật.</p>
 */
@Slf4j
@Service
public class FineractEndOfDayMonitor implements OperationalRefreshTask {

    static final String TASK_NAME = "fineract-eod";

    private final FineractJobGateway gateway;
    private final FineractEndOfDayProperties properties;
    private final boolean bookingProviderMock;
    private final Clock clock;
    /** Lần đọc Fineract thành công gần nhất; {@code null} khi chưa đọc được lần nào. */
    private volatile Observation observation;
    /** Mã lỗi của lần đọc gần nhất; {@code null} khi lần gần nhất thành công. */
    private volatile String lastCheckError;
    private Code lastLoggedCode;

    @Autowired
    public FineractEndOfDayMonitor(
            FineractJobGateway gateway,
            FineractEndOfDayProperties properties,
            @Value("${finora.fineract.booking-provider:mock}") String bookingProvider
    ) {
        this(gateway, properties, bookingProvider, Clock.systemUTC());
    }

    FineractEndOfDayMonitor(FineractJobGateway gateway, FineractEndOfDayProperties properties, String bookingProvider,
            Clock clock) {
        this.gateway = Objects.requireNonNull(gateway, "gateway");
        this.properties = Objects.requireNonNull(properties, "properties");
        this.bookingProviderMock = bookingProvider == null || bookingProvider.isBlank()
                || "mock".equalsIgnoreCase(bookingProvider.trim());
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Override
    public String name() {
        return TASK_NAME;
    }

    @Override
    public Duration interval() {
        return properties.refreshInterval();
    }

    /**
     * Đọc Fineract và lưu kết quả. Lỗi đọc được ghi lại (health hiện mã lỗi) rồi ném tiếp để luồng vận hành đếm vào
     * {@code finora.operations.task.failures{task=fineract-eod}}; số liệu của lần đọc thành công trước vẫn được giữ.
     */
    @Override
    public void refresh() {
        if (applicable()) {
            try {
                observation = observe(observation);
                lastCheckError = null;
            } catch (FineractIntegrationException exception) {
                lastCheckError = exception.getCode();
                throw exception;
            }
        }
        logTransition(currentStatus());
    }

    /** Đánh giá tại thời điểm gọi; không gọi Fineract. */
    public FineractEndOfDayStatus currentStatus() {
        Duration staleAfter = properties.staleAfter();
        if (!properties.enabled()) {
            return new FineractEndOfDayStatus(Code.NOT_APPLICABLE,
                    "Kiểm tra job cuối ngày của Fineract đang tắt (finora.loan.operations.fineract-eod.enabled=false)",
                    staleAfter, null, null, List.of());
        }
        if (bookingProviderMock) {
            return new FineractEndOfDayStatus(Code.NOT_APPLICABLE,
                    "Loan ghi khoản vay vào provider mock (FINERACT_BOOKING_PROVIDER=mock), DPD không lấy từ Fineract",
                    staleAfter, null, null, List.of());
        }
        Observation current = observation;
        String error = lastCheckError;
        if (current == null) {
            if (error == null) {
                return new FineractEndOfDayStatus(Code.NOT_CHECKED,
                        "Chưa tới lượt kiểm tra job cuối ngày đầu tiên sau khi khởi động", staleAfter, null, null, List.of());
            }
            return new FineractEndOfDayStatus(Code.CHECK_FAILED,
                    "Chưa đọc được job của Fineract (" + error + "); kiểm tra kết nối, mật khẩu API và quyền "
                            + "READ_SCHEDULER của tài khoản Fineract mà Loan dùng",
                    staleAfter, null, error, List.of());
        }
        Instant now = clock.instant();
        List<JobFreshness> jobs = current.jobs().stream().map(job -> freshness(job, now, staleAfter)).toList();
        return new FineractEndOfDayStatus(overall(jobs), message(jobs, staleAfter), staleAfter, current.checkedAt(),
                error, jobs);
    }

    /** Tuổi (giây) lần chạy thành công gần nhất của một job; NaN khi chưa biết hoặc kiểm tra không áp dụng. */
    public double lastSuccessAgeSeconds(String shortName) {
        Observation current = observation;
        if (!applicable() || current == null) {
            return Double.NaN;
        }
        Instant now = clock.instant();
        return current.jobs().stream()
                .filter(job -> job.shortName().equalsIgnoreCase(shortName))
                .findFirst()
                .map(JobObservation::lastSuccessAt)
                .map(at -> (double) Math.max(0, Duration.between(at, now).toSeconds()))
                .orElse(Double.NaN);
    }

    private boolean applicable() {
        return properties.enabled() && !bookingProviderMock;
    }

    private Observation observe(Observation previous) {
        Instant checkedAt = clock.instant();
        Map<String, FineractJob> byShortName = new HashMap<>();
        for (FineractJob job : gateway.listJobs()) {
            if (job.shortName() != null) {
                byShortName.putIfAbsent(job.shortName().toUpperCase(Locale.ROOT), job);
            }
        }
        List<JobObservation> jobs = new ArrayList<>();
        for (String shortName : properties.jobs()) {
            FineractJob job = byShortName.get(shortName.toUpperCase(Locale.ROOT));
            if (job == null) {
                jobs.add(new JobObservation(shortName, false, false, null, null, null, false));
                continue;
            }
            jobs.add(observeJob(shortName, job, previousSuccess(previous, shortName)));
        }
        return new Observation(checkedAt, jobs);
    }

    private JobObservation observeJob(String shortName, FineractJob job, Instant previousSuccess) {
        FineractJobRun last = job.lastRun();
        if (last == null) {
            return new JobObservation(shortName, true, job.active(), null, null, null, false);
        }
        if (last.succeeded()) {
            return new JobObservation(shortName, true, job.active(), last.status(), last.completedAt(),
                    last.completedAt(), false);
        }
        // Lần chạy cuối lỗi (ví dụ LA_RINT lỗi ở một khoản vay): dữ liệu vẫn tươi nếu lần thành công trước còn trong ngưỡng.
        try {
            Instant lastSuccess = gateway.recentRuns(job.jobId(), properties.historyLimit()).stream()
                    .filter(FineractJobRun::succeeded)
                    .map(FineractJobRun::completedAt)
                    .filter(Objects::nonNull)
                    .max(Comparator.naturalOrder())
                    .orElse(previousSuccess);
            return new JobObservation(shortName, true, job.active(), last.status(), last.completedAt(), lastSuccess,
                    false);
        } catch (FineractIntegrationException historyError) {
            // Lần thành công đã thấy ở lần đọc trước vẫn đúng ("ít nhất gần như vậy"); chưa thấy thì báo thiếu lịch sử.
            return new JobObservation(shortName, true, job.active(), last.status(), last.completedAt(), previousSuccess,
                    previousSuccess == null);
        }
    }

    private static Instant previousSuccess(Observation previous, String shortName) {
        if (previous == null) {
            return null;
        }
        return previous.jobs().stream()
                .filter(job -> job.shortName().equalsIgnoreCase(shortName))
                .map(JobObservation::lastSuccessAt)
                .filter(Objects::nonNull)
                .findFirst()
                .orElse(null);
    }

    private static JobFreshness freshness(JobObservation job, Instant now, Duration staleAfter) {
        Duration age = job.lastSuccessAt() == null ? null : Duration.between(job.lastSuccessAt(), now);
        JobState state;
        if (!job.found()) {
            state = JobState.MISSING;
        } else if (job.lastSuccessAt() == null) {
            state = job.historyUnavailable() ? JobState.HISTORY_UNAVAILABLE : JobState.NEVER_SUCCEEDED;
        } else if (age.compareTo(staleAfter) > 0) {
            state = JobState.STALE;
        } else {
            state = JobState.FRESH;
        }
        return new JobFreshness(job.shortName(), state, job.active(), job.lastRunStatus(), job.lastRunAt(),
                job.lastSuccessAt(), age);
    }

    private static Code overall(List<JobFreshness> jobs) {
        if (jobs.stream().anyMatch(job -> job.state() == JobState.MISSING)) {
            return Code.JOB_MISSING;
        }
        if (jobs.stream().anyMatch(job -> job.state() == JobState.STALE || job.state() == JobState.NEVER_SUCCEEDED)) {
            return Code.STALE;
        }
        if (jobs.stream().anyMatch(job -> job.state() == JobState.HISTORY_UNAVAILABLE)) {
            return Code.HISTORY_UNAVAILABLE;
        }
        return Code.FRESH;
    }

    private static String message(List<JobFreshness> jobs, Duration staleAfter) {
        String threshold = describe(staleAfter);
        List<String> missing = names(jobs, JobState.MISSING);
        List<String> stale = jobs.stream()
                .filter(job -> job.state() == JobState.STALE || job.state() == JobState.NEVER_SUCCEEDED)
                .map(job -> job.lastSuccessAt() == null
                        ? job.shortName() + " (chưa từng chạy thành công)"
                        : job.shortName() + " (thành công gần nhất " + job.lastSuccessAt() + ")")
                .toList();
        List<String> inactive = jobs.stream()
                .filter(job -> job.state() != JobState.MISSING && !job.active())
                .map(JobFreshness::shortName)
                .toList();
        String message;
        if (!missing.isEmpty()) {
            message = "Fineract không có job bắt buộc: " + String.join(", ", missing)
                    + "; kiểm tra tenant và phiên bản Fineract";
        } else if (!stale.isEmpty()) {
            message = "Job cuối ngày chưa chạy thành công trong " + threshold + ": " + String.join(", ", stale)
                    + "; DPD, nhóm nợ và số khắc phục quá hạn có thể đứng yên";
        } else if (!names(jobs, JobState.HISTORY_UNAVAILABLE).isEmpty()) {
            message = "Lần chạy cuối lỗi và không đọc được lịch sử để tìm lần thành công: "
                    + String.join(", ", names(jobs, JobState.HISTORY_UNAVAILABLE));
        } else {
            message = "Job cuối ngày đã chạy thành công trong " + threshold;
        }
        return inactive.isEmpty() ? message
                : message + " (cron đang tắt, chỉ chạy khi gọi tay: " + String.join(", ", inactive) + ")";
    }

    private static List<String> names(List<JobFreshness> jobs, JobState state) {
        return jobs.stream().filter(job -> job.state() == state).map(JobFreshness::shortName).toList();
    }

    private static String describe(Duration duration) {
        return duration.toMinutesPart() == 0 && duration.toSecondsPart() == 0
                ? duration.toHours() + " giờ" : duration.toString();
    }

    private void logTransition(FineractEndOfDayStatus status) {
        Code previous = lastLoggedCode;
        lastLoggedCode = status.code();
        if (previous == status.code()) {
            return;
        }
        if (status.code() == Code.STALE || status.code() == Code.JOB_MISSING) {
            log.warn("Job cuối ngày Fineract cần xử lý: code={}, jobs={}, message={}", status.code(),
                    status.jobs().stream().map(job -> job.shortName() + "=" + job.state())
                            .collect(Collectors.joining(",")), status.message());
        } else {
            log.info("Job cuối ngày Fineract: code={}, previous={}", status.code(), previous);
        }
    }

    private record Observation(Instant checkedAt, List<JobObservation> jobs) {
    }

    private record JobObservation(
            String shortName,
            boolean found,
            boolean active,
            String lastRunStatus,
            Instant lastRunAt,
            Instant lastSuccessAt,
            boolean historyUnavailable
    ) {
    }
}
