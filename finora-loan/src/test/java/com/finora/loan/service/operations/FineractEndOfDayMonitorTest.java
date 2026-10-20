package com.finora.loan.service.operations;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.finora.loan.config.FineractEndOfDayProperties;
import com.finora.loan.integration.fineract.client.FineractIntegrationException;
import com.finora.loan.integration.fineract.client.FineractJobGateway;
import com.finora.loan.integration.fineract.contract.FineractJob;
import com.finora.loan.integration.fineract.contract.FineractJobRun;
import com.finora.loan.service.operations.FineractEndOfDayStatus.Code;
import com.finora.loan.service.operations.FineractEndOfDayStatus.JobState;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.Status;

class FineractEndOfDayMonitorTest {

    /** 2026-10-17 00:01 giờ Việt Nam: đúng lúc cron EOD của production chạy. */
    private static final Instant MIDNIGHT_RUN = Instant.parse("2026-10-16T17:01:00Z");

    private final MutableClock clock = new MutableClock(MIDNIGHT_RUN.plus(Duration.ofHours(2)));
    private final FakeJobGateway gateway = new FakeJobGateway();
    private final FineractEndOfDayProperties properties = new FineractEndOfDayProperties(true, Duration.ofHours(26),
            Duration.ofMinutes(5), List.of("LA_RINT", "LA_ARAG"), 50);

    @Test
    void bothJobsSucceededWithinThresholdIsUp() {
        gateway.job(7, "LA_RINT", true, success(MIDNIGHT_RUN));
        gateway.job(12, "LA_ARAG", true, success(MIDNIGHT_RUN.plusSeconds(30)));
        FineractEndOfDayMonitor monitor = monitor("fineract");

        monitor.refresh();

        FineractEndOfDayStatus status = monitor.currentStatus();
        assertThat(status.code()).isEqualTo(Code.FRESH);
        assertThat(status.jobs()).extracting(FineractEndOfDayStatus.JobFreshness::state)
                .containsExactly(JobState.FRESH, JobState.FRESH);
        assertThat(health(monitor).getStatus()).isEqualTo(Status.UP);
        // Mốc là lúc job kết thúc (bắt đầu + 5 giây).
        assertThat(monitor.lastSuccessAgeSeconds("LA_RINT")).isEqualTo(Duration.ofHours(2).minusSeconds(5).toSeconds());
        assertThat(gateway.historyCalls).isEmpty();
    }

    @Test
    void crossingTheThresholdTurnsDownWithoutCallingFineractAgain() {
        gateway.job(7, "LA_RINT", true, success(MIDNIGHT_RUN));
        gateway.job(12, "LA_ARAG", true, success(MIDNIGHT_RUN));
        FineractEndOfDayMonitor monitor = monitor("fineract");
        monitor.refresh();
        int listCalls = gateway.listCalls;
        Instant finishedAt = MIDNIGHT_RUN.plusSeconds(5);

        clock.set(finishedAt.plus(Duration.ofHours(26)));
        assertThat(monitor.currentStatus().code()).isEqualTo(Code.FRESH);

        // Cron đêm sau không chạy: vượt 26 giờ là DOWN dù chưa đọc lại Fineract.
        clock.set(finishedAt.plus(Duration.ofHours(26)).plusSeconds(1));
        FineractEndOfDayStatus status = monitor.currentStatus();
        Health health = health(monitor);

        assertThat(gateway.listCalls).isEqualTo(listCalls);
        assertThat(status.code()).isEqualTo(Code.STALE);
        assertThat(health.getStatus()).isEqualTo(Status.DOWN);
        assertThat(health.getDetails()).containsEntry("reason", "FINERACT_EOD_STALE");
        assertThat((String) health.getDetails().get("message"))
                .contains("26 giờ").contains("LA_RINT").contains("LA_ARAG").contains("DPD");
    }

    @Test
    void failedLastRunIsStillFreshWhenTheHistoryHasARecentSuccess() {
        // LA_RINT đêm nay lỗi ở một khoản vay, lần thành công hôm qua vẫn trong 26 giờ.
        gateway.job(7, "LA_RINT", true, failed(MIDNIGHT_RUN));
        gateway.history(7, List.of(failed(MIDNIGHT_RUN), success(MIDNIGHT_RUN.minus(Duration.ofHours(23)))));
        gateway.job(12, "LA_ARAG", true, success(MIDNIGHT_RUN));
        FineractEndOfDayMonitor monitor = monitor("fineract");

        monitor.refresh();

        FineractEndOfDayStatus status = monitor.currentStatus();
        assertThat(status.code()).isEqualTo(Code.FRESH);
        assertThat(status.jobs().get(0).lastRunStatus()).isEqualTo("failed");
        assertThat(status.jobs().get(0).lastSuccessAt())
                .isEqualTo(MIDNIGHT_RUN.minus(Duration.ofHours(23)).plusSeconds(5));
        assertThat(gateway.historyCalls).containsExactly("7:50");
    }

    @Test
    void jobThatNeverSucceededIsStale() {
        gateway.job(7, "LA_RINT", true, failed(MIDNIGHT_RUN));
        gateway.history(7, List.of(failed(MIDNIGHT_RUN), failed(MIDNIGHT_RUN.minus(Duration.ofDays(1)))));
        gateway.job(12, "LA_ARAG", false, null);
        FineractEndOfDayMonitor monitor = monitor("fineract");

        monitor.refresh();

        FineractEndOfDayStatus status = monitor.currentStatus();
        assertThat(status.code()).isEqualTo(Code.STALE);
        assertThat(status.jobs()).extracting(FineractEndOfDayStatus.JobFreshness::state)
                .containsExactly(JobState.NEVER_SUCCEEDED, JobState.NEVER_SUCCEEDED);
        assertThat(status.message()).contains("chưa từng chạy thành công").contains("cron đang tắt").contains("LA_ARAG");
        assertThat(monitor.lastSuccessAgeSeconds("LA_ARAG")).isNaN();
    }

    @Test
    void missingRequiredJobIsDown() {
        gateway.job(12, "LA_ARAG", true, success(MIDNIGHT_RUN));
        FineractEndOfDayMonitor monitor = monitor("fineract");

        monitor.refresh();

        Health health = health(monitor);
        assertThat(health.getStatus()).isEqualTo(Status.DOWN);
        assertThat(health.getDetails()).containsEntry("reason", "FINERACT_EOD_JOB_MISSING");
        assertThat((String) health.getDetails().get("message")).contains("LA_RINT");
    }

    @Test
    void unreachableFineractBeforeAnySuccessfulCheckIsUnknownAndCounted() {
        gateway.listFailure = new FineractIntegrationException("FINERACT_UNAVAILABLE", "down", true, null);
        FineractEndOfDayMonitor monitor = monitor("fineract");

        // Ném tiếp để luồng vận hành đếm vào finora.operations.task.failures.
        assertThatThrownBy(monitor::refresh).isSameAs(gateway.listFailure);

        Health health = health(monitor);
        assertThat(health.getStatus()).isEqualTo(Status.UNKNOWN);
        assertThat(health.getDetails()).containsEntry("reason", "FINERACT_EOD_CHECK_FAILED")
                .containsEntry("checkError", "FINERACT_UNAVAILABLE");
    }

    @Test
    void lastKnownSuccessKeepsAgingWhileFineractIsUnreachable() {
        gateway.job(7, "LA_RINT", true, success(MIDNIGHT_RUN));
        gateway.job(12, "LA_ARAG", true, success(MIDNIGHT_RUN));
        FineractEndOfDayMonitor monitor = monitor("fineract");
        monitor.refresh();

        gateway.listFailure = new FineractIntegrationException("FINERACT_TIMEOUT", "timeout", true, null);
        clock.set(MIDNIGHT_RUN.plus(Duration.ofHours(3)));
        assertThatThrownBy(monitor::refresh).isInstanceOf(FineractIntegrationException.class);
        Health stillFresh = health(monitor);
        assertThat(stillFresh.getStatus()).isEqualTo(Status.UP);
        assertThat(stillFresh.getDetails()).containsEntry("checkError", "FINERACT_TIMEOUT");

        clock.set(MIDNIGHT_RUN.plus(Duration.ofHours(30)));
        assertThat(health(monitor).getStatus()).isEqualTo(Status.DOWN);

        gateway.listFailure = null;
        gateway.job(7, "LA_RINT", true, success(MIDNIGHT_RUN.plus(Duration.ofHours(24))));
        gateway.job(12, "LA_ARAG", true, success(MIDNIGHT_RUN.plus(Duration.ofHours(24))));
        monitor.refresh();
        assertThat(health(monitor).getStatus()).isEqualTo(Status.UP);
        assertThat(health(monitor).getDetails()).doesNotContainKey("checkError");
    }

    @Test
    void historyUnavailableWithoutEarlierSuccessIsUnknownButReusesAKnownSuccess() {
        gateway.job(7, "LA_RINT", true, success(MIDNIGHT_RUN));
        gateway.job(12, "LA_ARAG", true, success(MIDNIGHT_RUN));
        FineractEndOfDayMonitor monitor = monitor("fineract");
        monitor.refresh();

        // Đêm sau LA_RINT lỗi và runhistory bị từ chối: lần thành công đã thấy vẫn được dùng.
        gateway.job(7, "LA_RINT", true, failed(MIDNIGHT_RUN.plus(Duration.ofDays(1))));
        gateway.historyFailure = new FineractIntegrationException("FINERACT_REQUEST_REJECTED", "400", false, null);
        clock.set(MIDNIGHT_RUN.plus(Duration.ofHours(25)));
        monitor.refresh();
        assertThat(monitor.currentStatus().code()).isEqualTo(Code.FRESH);
        assertThat(monitor.currentStatus().jobs().get(0).lastSuccessAt()).isEqualTo(MIDNIGHT_RUN.plusSeconds(5));

        FineractEndOfDayMonitor fresh = monitor("fineract");
        fresh.refresh();
        assertThat(fresh.currentStatus().code()).isEqualTo(Code.HISTORY_UNAVAILABLE);
        assertThat(health(fresh).getStatus()).isEqualTo(Status.UNKNOWN);
    }

    @Test
    void mockBookingProviderIsNotApplicableAndNeverCallsFineract() {
        FineractEndOfDayMonitor monitor = monitor("mock");

        monitor.refresh();

        Health health = health(monitor);
        assertThat(health.getStatus()).isEqualTo(Status.UNKNOWN);
        assertThat(health.getDetails()).containsEntry("reason", "FINERACT_EOD_NOT_APPLICABLE");
        assertThat(gateway.listCalls).isZero();
        assertThat(monitor.lastSuccessAgeSeconds("LA_ARAG")).isNaN();
    }

    @Test
    void disabledCheckIsNotApplicable() {
        FineractEndOfDayProperties disabled = new FineractEndOfDayProperties(false, Duration.ofHours(26),
                Duration.ofMinutes(5), List.of("LA_RINT", "LA_ARAG"), 50);
        FineractEndOfDayMonitor monitor = new FineractEndOfDayMonitor(gateway, disabled, "fineract", clock);

        monitor.refresh();

        assertThat(monitor.currentStatus().code()).isEqualTo(Code.NOT_APPLICABLE);
        assertThat(gateway.listCalls).isZero();
    }

    @Test
    void beforeTheFirstCheckTheStatusIsNotCheckedYet() {
        FineractEndOfDayMonitor monitor = monitor("fineract");

        assertThat(monitor.currentStatus().code()).isEqualTo(Code.NOT_CHECKED);
        assertThat(health(monitor).getStatus()).isEqualTo(Status.UNKNOWN);
    }

    @Test
    void propertiesRejectEmptyJobListAndInvalidHistoryLimit() {
        assertThatThrownBy(() -> new FineractEndOfDayProperties(true, Duration.ofHours(26), Duration.ofMinutes(5),
                List.of(" "), 50)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new FineractEndOfDayProperties(true, Duration.ofHours(26), Duration.ofMinutes(5),
                List.of("LA_ARAG"), 0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new FineractEndOfDayProperties(true, Duration.ZERO, Duration.ofMinutes(5),
                List.of("LA_ARAG"), 50)).isInstanceOf(IllegalArgumentException.class);
    }

    private FineractEndOfDayMonitor monitor(String bookingProvider) {
        return new FineractEndOfDayMonitor(gateway, properties, bookingProvider, clock);
    }

    private static Health health(FineractEndOfDayMonitor monitor) {
        return new FineractEndOfDayHealthIndicator(monitor).health();
    }

    private static FineractJobRun success(Instant startedAt) {
        return new FineractJobRun("success", startedAt, startedAt.plusSeconds(5));
    }

    private static FineractJobRun failed(Instant startedAt) {
        return new FineractJobRun("failed", startedAt, startedAt.plusSeconds(5));
    }

    /** Gateway giả: trả đúng job/lịch sử đã khai báo, ghi lại số lần gọi. */
    private static final class FakeJobGateway implements FineractJobGateway {
        private final Map<String, FineractJob> jobs = new HashMap<>();
        private final Map<Long, List<FineractJobRun>> histories = new HashMap<>();
        private final List<String> historyCalls = new ArrayList<>();
        private int listCalls;
        private FineractIntegrationException listFailure;
        private FineractIntegrationException historyFailure;

        void job(long id, String shortName, boolean active, FineractJobRun lastRun) {
            jobs.put(shortName, new FineractJob(id, shortName, shortName, active, false, lastRun));
        }

        void history(long id, List<FineractJobRun> runs) {
            histories.put(id, runs);
        }

        @Override
        public List<FineractJob> listJobs() {
            listCalls++;
            if (listFailure != null) {
                throw listFailure;
            }
            return List.copyOf(jobs.values());
        }

        @Override
        public List<FineractJobRun> recentRuns(long jobId, int limit) {
            historyCalls.add(jobId + ":" + limit);
            if (historyFailure != null) {
                throw historyFailure;
            }
            return histories.getOrDefault(jobId, List.of());
        }
    }

    private static final class MutableClock extends Clock {
        private Instant now;

        MutableClock(Instant now) {
            this.now = now;
        }

        void set(Instant instant) {
            now = instant;
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
